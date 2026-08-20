package extent

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.context.TestCaseContext
import com.kms.katalon.core.context.TestSuiteContext
import com.kms.katalon.core.logging.KeywordLogger
import com.kms.katalon.core.logging.TestSuiteXMLLogParser
import com.kms.katalon.core.logging.model.ILogRecord
import com.kms.katalon.core.logging.model.TestCaseLogRecord
import com.kms.katalon.core.logging.model.TestStatus
import com.kms.katalon.core.logging.model.TestStepLogRecord
import com.kms.katalon.core.logging.model.TestSuiteLogRecord
import com.kms.katalon.core.webui.driver.DriverFactory
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.WebDriver

import com.aventstack.extentreports.ExtentReports
import com.aventstack.extentreports.ExtentTest
import com.aventstack.extentreports.MediaEntityBuilder
import com.aventstack.extentreports.Status
import com.aventstack.extentreports.reporter.ExtentSparkReporter
import com.aventstack.extentreports.reporter.configuration.Theme

import java.io.FileFilter
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Bridges Katalon Studio's Test Listener lifecycle to Extent Reports.
 *
 * Extent Reports has no per-result file format of its own to write to
 * incrementally, and its official cross-run JSON merge feature
 * (ExtentReports.createDomainFromJsonArchive) doesn't work reliably on
 * JDK 17+. So nothing here builds a live ExtentReports/ExtentTest object
 * graph mid-run. Every test case's outcome - status, timing, message, a
 * screenshot file, and (once parsed) its step tree - is written as a small,
 * immutable JSON record under extent.results.dir the moment it's known.
 * The real ExtentReports API (createTest/createNode/log/flush) is only
 * touched once per run, by whichever suite's AfterTestSuite is determined
 * to be the last one - see generateHtmlReport(). This also avoids relying
 * on any in-memory state surviving between BeforeTestSuite, a TestCase,
 * and AfterTestSuite.
 *
 * Every public method here catches Throwable and only logs a warning: a
 * bug in report generation must never fail, skip, or change the outcome of
 * the real test it's reporting on.
 */
class ExtentReportBridge {

    private static final KeywordLogger logger = KeywordLogger.getInstance(ExtentReportBridge.class)

    /** Last-resort fallback only - see the class-level notes on cross-phase state. */
    private static volatile String currentSuiteName = 'Katalon Test Suite'

    /** Guards every file this bridge writes/reads under extent.results.dir - see withRunLock(). */
    private static final Object INTRA_JVM_LOCK = new Object()

    /**
     * Buffers ExtentKeywords.step()/log() calls made during ONE test case's
     * own execution (BeforeTestCase -> test body -> AfterTestCase), and
     * tracks that same test case's start time. Keyed by testCaseId rather
     * than a ThreadLocal: Katalon does not guarantee BeforeTestCase and
     * AfterTestCase of the same test case run on the same thread, so a
     * plain map keyed by testCaseId is used instead - it only relies on
     * both phases running in the same JVM process, which is always true
     * (only *different suites* in a Test Suite Collection can span
     * separate processes; the phases of one test case within one suite
     * never do).
     */
    private static final Map<String, Long> testCaseStartTimes = new ConcurrentHashMap<>()

    private static final Map<String, List<Map>> testCaseManualEntries = new ConcurrentHashMap<>()

    /**
     * Lets ExtentKeywords.step()/logManual()/attachScreenshot() - called
     * from arbitrary points inside a Test Case script, which never receive
     * a TestCaseContext - find which testCaseManualEntries bucket to write
     * into. Only needs to survive from startTestCase() to that same test
     * case's own script body running its own keyword calls, a single
     * in-order call sequence on one thread - the actual buffered data
     * lives in the shared, thread-agnostic testCaseManualEntries map, so
     * this only matters for attributing an entry to the right test case.
     */
    private static final ThreadLocal<String> currentTestCaseId = new ThreadLocal<>()

    // ------------------------------------------------------------------
    // Test Listener entry points
    // ------------------------------------------------------------------

    static void startSuite(TestSuiteContext testSuiteContext) {
        try {
            if (!ExtentConfig.isEnabled()) {
                return
            }
            File resultsDir = ExtentConfig.getResultsDir()
            resultsDir.mkdirs()
            new File(resultsDir, 'attachments').mkdirs()

            withRunLock(resultsDir) {
                File thisRunDir = currentRunDir()
                Map marker = readRunMarker(resultsDir)
                String previousRunDir = marker.runDir as String
                boolean continuingRun
                if (thisRunDir != null) {
                    continuingRun = thisRunDir.absolutePath == previousRunDir
                } else {
                    // An inconclusive signal is treated as "still
                    // continuing" rather than risk wiping an in-progress
                    // Collection's results out from under it.
                    continuingRun = previousRunDir != null && !previousRunDir.trim().isEmpty()
                }
                if (!continuingRun) {
                    if (ExtentConfig.cleanResultsBeforeRun()) {
                        clearPreviousResults(resultsDir)
                    }
                    marker.reportPath = ''
                    new File(resultsDir, '.extent-collection-progress.txt').delete()
                }
                String runDirToRecord = thisRunDir != null ? thisRunDir.absolutePath : previousRunDir
                writeRunMarker(resultsDir, runDirToRecord ?: '', marker.reportPath as String)
            }

            currentSuiteName = resolveSuiteName(testSuiteContext)
            logger.logInfo("[Extent] Reporting enabled. Results directory: ${resultsDir.absolutePath}")
        } catch (Throwable t) {
            logger.logWarning("[Extent] Failed to initialize Extent reporting: ${t}")
        }
    }

    static void startTestCase(TestCaseContext testCaseContext) {
        try {
            if (!ExtentConfig.isEnabled()) {
                return
            }
            String testCaseId = testCaseContext.getTestCaseId()
            testCaseManualEntries[testCaseId] = []
            testCaseStartTimes[testCaseId] = System.currentTimeMillis()
            currentTestCaseId.set(testCaseId)
        } catch (Throwable t) {
            logger.logWarning("[Extent] Failed to start test case reporting: ${t}")
        }
    }

    static void finishTestCase(TestCaseContext testCaseContext) {
        try {
            if (!ExtentConfig.isEnabled()) {
                return
            }
            File resultsDir = ExtentConfig.getResultsDir()
            String uuid = UUID.randomUUID().toString()
            String testCaseId = testCaseContext.getTestCaseId()
            String name = readableName(testCaseId)
            String suiteName = resolveSuiteNameQuiet() ?: currentSuiteName ?: 'Suite'
            String katalonStatus = (testCaseContext.getTestCaseStatus() ?: 'FAILED').toUpperCase()
            String message = testCaseContext.getMessage()

            String screenshotFile = null
            boolean shouldScreenshot = ExtentConfig.attachScreenshotAlways() ||
                (ExtentConfig.attachScreenshotOnFailure() && katalonStatus != 'PASSED')
            if (shouldScreenshot) {
                screenshotFile = captureScreenshot(resultsDir, uuid)
            }

            String activeBrowser = detectActiveBrowser()
            String suiteInstance = safeCall { suiteInstanceKey(currentRunDir()) } ?: suiteName

            Map record = [
                uuid           : uuid,
                testCaseId     : testCaseId,
                name           : name,
                suiteName      : suiteName,
                suiteInstance  : suiteInstance,
                browser        : activeBrowser,
                status         : katalonStatus,
                start          : testCaseStartTimes.remove(testCaseId) ?: System.currentTimeMillis(),
                stop           : System.currentTimeMillis(),
                message        : message,
                screenshotFile : screenshotFile,
                manualEntries  : testCaseManualEntries.remove(testCaseId) ?: [],
                autoSteps      : null,
                logFolder      : safeCall { RunConfiguration.getReportFolder() },
            ]

            withRunLock(resultsDir) {
                new File(resultsDir, "${uuid}.json").text = new JsonBuilder(record).toPrettyString()
            }

            // Steps are captured later, from this suite's own
            // AfterTestSuite - see processPendingSteps() for why this
            // can't safely happen from inside AfterTestCase itself
            // (Katalon keeps the execution log open until every
            // AfterTestCase listener for this test case has returned).
            if (ExtentConfig.captureSteps() && record.logFolder) {
                queuePendingSteps(resultsDir, uuid, testCaseId)
            }

            currentTestCaseId.remove()
        } catch (Throwable t) {
            logger.logWarning("[Extent] Failed to finish test case reporting: ${t}")
        }
    }

    static void finishSuite(TestSuiteContext testSuiteContext) {
        try {
            if (!ExtentConfig.isEnabled()) {
                return
            }
            File resultsDir = ExtentConfig.getResultsDir()
            logger.logInfo("[Extent] Suite finished. Results: ${resultsDir.absolutePath}")
            String suiteName = resolveSuiteName(testSuiteContext)

            // Resolved once, right here, rather than letting
            // shouldGenerateReportNow() re-resolve it fresh later:
            // processPendingSteps() below can block for up to 20 real
            // seconds on the final suite (see
            // retryPendingStepsSynchronously()), and RunConfiguration.
            // getReportFolder() reflects Katalon's *current* execution
            // context - after that delay it can no longer be trusted to
            // resolve the same run directory it did a moment earlier.
            File runDir = currentRunDir()

            // Computed once - has a side effect (records this suite as
            // complete) - see shouldGenerateReportNow().
            boolean isFinalSuite = shouldGenerateReportNow(resultsDir, runDir)

            if (ExtentConfig.captureSteps()) {
                processPendingSteps(resultsDir, isFinalSuite)
            }

            if (isFinalSuite) {
                generateHtmlReport(resultsDir, suiteName)
            }
        } catch (Throwable ignored) {
            // never fail the suite because of reporting
        }
    }

    // ------------------------------------------------------------------
    // Manual keyword support (ExtentKeywords) - buffers into the current
    // test case's bucket in testCaseManualEntries (looked up via
    // currentTestCaseId); see finishTestCase() above for where this gets
    // folded into that test case's JSON record.
    // ------------------------------------------------------------------

    private static void addManualEntry(Map entry) {
        String testCaseId = currentTestCaseId.get()
        if (testCaseId == null) {
            return
        }
        List<Map> bucket = testCaseManualEntries[testCaseId]
        if (bucket == null) {
            return
        }
        bucket << entry
    }

    static void logManual(String status, String name, String details) {
        try {
            addManualEntry([status: status, name: name, details: details, screenshotFile: null])
        } catch (Throwable ignored) { }
    }

    static void step(String stepName, Closure body) {
        long start = System.currentTimeMillis()
        try {
            body.call()
            addManualEntry([status: 'PASS', name: stepName, details: null, screenshotFile: null, start: start, stop: System.currentTimeMillis()])
        } catch (Throwable t) {
            addManualEntry([status: 'FAIL', name: stepName, details: t.getMessage(), screenshotFile: null, start: start, stop: System.currentTimeMillis()])
            throw t
        }
    }

    static void attachScreenshot(String name) {
        try {
            File resultsDir = ExtentConfig.getResultsDir()
            String file = captureScreenshot(resultsDir, UUID.randomUUID().toString())
            if (file) {
                addManualEntry([status: 'INFO', name: name, details: null, screenshotFile: file])
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------
    // Final report assembly - runs exactly once per run, in whichever
    // suite's process is determined to finish last.
    // ------------------------------------------------------------------

    /**
     * Reads every intermediate *.json record under resultsDir (written by
     * this run's own suite(s) - possibly across more than one OS process
     * for a Test Suite Collection with isolated-process suites, since they
     * all still share this one directory on the shared filesystem) and
     * replays them through the real Extent Reports API exactly once, then
     * flushes the Spark HTML report. Never throws - if anything here goes
     * wrong, this logs one warning and the suite result is unaffected.
     */
    private static void generateHtmlReport(File resultsDir, String suiteName) {
        if (!ExtentConfig.autoGenerateReport()) {
            return
        }
        try {
            List<File> recordFiles = withRunLock(resultsDir) {
                resultsDir.listFiles({ File f -> f.isFile() && f.name.endsWith('.json') } as FileFilter)?.toList() ?: []
            } as List<File>
            if (!recordFiles) {
                logger.logWarning('[Extent] No test case records found - skipping report generation.')
                return
            }

            JsonSlurper slurper = new JsonSlurper()
            List<Map> records = recordFiles.collect { File f ->
                try {
                    return slurper.parse(f) as Map
                } catch (Throwable t) {
                    logger.logWarning("[Extent] Skipping unreadable result record ${f.name}: ${t.getMessage()}")
                    return null
                }
            }.findAll { it != null }

            // Suites are ordered alphabetically by suite name. Start time
            // is the tiebreaker: first for ordering repeat occurrences of
            // the very same suite name relative to each other (by whichever
            // occurrence actually started first), then for ordering test
            // cases within one occurrence.
            Map<String, Long> firstStartByInstance = [:]
            records.each { Map record ->
                String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
                long start = record.start as Long
                Long existing = firstStartByInstance[rawInstance]
                if (existing == null || start < existing) {
                    firstStartByInstance[rawInstance] = start
                }
            }
            records.sort { Map record ->
                String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
                [bareSuiteName(rawInstance).toLowerCase(), firstStartByInstance[rawInstance], record.start as Long]
            }
            assignDisplayLabels(records)

            // The real aggregate run window - true min(start)/max(stop)
            // across every record - computed here because Extent's own
            // Dashboard "Started"/"Ended" summary cards always reset to the
            // flush() instant regardless of what's set on the Report model
            // beforehand. Computed correctly here in Groovy, then written
            // into the two Dashboard cards client-side below.
            String timeStampFormat = 'MMM d, yyyy hh:mm:ss a'
            java.text.SimpleDateFormat tsFormat = new java.text.SimpleDateFormat(timeStampFormat)
            String realStarted = tsFormat.format(new Date(records.collect { it.start as Long }.min()))
            String realEnded = tsFormat.format(new Date(records.collect { it.stop as Long }.max()))

            File reportBaseDir = ExtentConfig.getReportDir()
            reportBaseDir.mkdirs()
            String timestamp = new java.text.SimpleDateFormat('yyyyMMdd_HHmmss').format(new Date())
            String displayName = sanitizeForFilename(suiteName)
            File runReportDir = new File(reportBaseDir, "${displayName}_${timestamp}")
            runReportDir.mkdirs()

            ExtentSparkReporter spark = new ExtentSparkReporter(new File(runReportDir, 'Index.html'))
            spark.config().setDocumentTitle("${suiteName} - Extent Report")
            spark.config().setReportName(suiteName)
            spark.config().setTheme('dark' == ExtentConfig.getReportTheme() ? Theme.DARK : Theme.STANDARD)
            // ExtentTest.assignAuthor() is repurposed above to carry suite
            // grouping (see assignDisplayLabels()), since Extent has no
            // native "Suites" concept of its own - but the tab itself is
            // still hardcoded to read "Author" inside Extent's own shipped
            // template, not exposed through any config API. Relabeled
            // client-side instead of forking that template.
            spark.config().setJs("""
(function() {
    var suitesTabHeader = document.querySelector(".author-view .test-list-tools span.font-size-14");
    if (suitesTabHeader) { suitesTabHeader.textContent = "Suites"; }
    var suitesDashboardCard = document.querySelector(".author-container .card-header p");
    if (suitesDashboardCard) { suitesDashboardCard.textContent = "Suites"; }

    // See the comment above realStarted/realEnded in ExtentReportBridge -
    // these two values are computed from real Katalon execution data, not
    // from Extent's own (unreliable, at the report-aggregate level) timing.
    document.querySelectorAll(".card-body").forEach(function(card) {
        var label = card.querySelector("p.m-b-0");
        if (!label) { return; }
        var h3 = card.querySelector("h3");
        if (!h3) { return; }
        if (label.textContent.trim() === "Started") { h3.textContent = "${realStarted}"; }
        if (label.textContent.trim() === "Ended") { h3.textContent = "${realEnded}"; }
    });

    // The Suites (Author) tab's card order is independent of the order
    // tests are created in on the Groovy side, so the rendered cards are
    // re-sorted alphabetically by their visible name here instead.
    var suitesList = document.querySelector(".author-view .test-list-item");
    if (suitesList) {
        var cards = Array.prototype.slice.call(suitesList.children);
        cards.sort(function(a, b) {
            var nameA = (a.querySelector(".test-detail p.name") || {}).textContent || "";
            var nameB = (b.querySelector(".test-detail p.name") || {}).textContent || "";
            return nameA.trim().toLowerCase().localeCompare(nameB.trim().toLowerCase());
        });
        cards.forEach(function(card) { suitesList.appendChild(card); });
    }
})();
""")
            // Copies the CSS/JS/icons Extent Reports ships inside its own
            // jar into a sibling "spark/" folder next to Index.html, so the
            // report opens correctly straight from file:// - no internet
            // connection and no local server needed.
            spark.config().enableOfflineMode(true)

            ExtentReports extent = new ExtentReports()
            extent.attachReporter(spark)
            writeSystemInfo(extent, records)

            List<Map> categoryDefs = loadCategoryDefinitions()

            records.each { Map record -> replayTestCase(extent, record, resultsDir, categoryDefs) }

            extent.flush()

            withRunLock(resultsDir) {
                Map marker = readRunMarker(resultsDir)
                String previousPath = marker.reportPath as String
                if (previousPath) {
                    File previous = new File(previousPath).parentFile
                    if (previous != null && previous.exists() && previous.canonicalPath != runReportDir.canonicalPath) {
                        deleteRecursively(previous)
                    }
                }
                writeRunMarker(resultsDir, marker.runDir as String, new File(runReportDir, 'Index.html').absolutePath)
            }

            logger.logInfo("[Extent] HTML report ready: ${new File(runReportDir, 'Index.html').absolutePath}")
        } catch (Throwable t) {
            logger.logWarning("[Extent] Could not assemble the HTML report: ${t}")
        }
    }

    /**
     * Every distinct suite occurrence Katalon actually ran (identified by
     * suiteInstanceKey()'s raw, per-occurrence value) gets its own,
     * separate group in the report - two occurrences of the same suite are
     * always shown separately, whether they ran with the same browser, a
     * different browser, the same test cases, or a different mix. What
     * changes here is only the label: Katalon's own disambiguation suffix
     * on a repeat occurrence's report folder is an opaque hash, meaningless
     * to a report reader. This replaces it with the occurrence's own
     * browser where that's already enough to tell two occurrences of the
     * same suite apart (the common case), falling back to a plain "#2"/"#3"
     * ordinal - ordered by which occurrence actually started first - only
     * when even name+browser collide (two occurrences of the same suite
     * run with the same browser).
     *
     * Grouping key is the raw suiteInstance, not individual test-case
     * records: multiple test cases sharing one ordinary suite occurrence
     * (e.g. "API Test Suite" running both an API-only test case with no
     * browser and a WebUI one with a browser) must resolve to the exact
     * same label.
     */
    private static void assignDisplayLabels(List<Map> records) {
        Map<String, Map> firstRecordByInstance = [:]
        // Whether ANY test case within this occurrence used a browser -
        // not just whichever test case happened to start first. A suite
        // occurrence can legitimately mix an API-only test case (no
        // browser) with a WebUI one (a browser).
        Map<String, String> browserByInstance = [:]
        records.each { Map record ->
            String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
            if (!firstRecordByInstance.containsKey(rawInstance)) {
                firstRecordByInstance[rawInstance] = record
            }
            if (record.browser && !browserByInstance[rawInstance]) {
                browserByInstance[rawInstance] = record.browser as String
            }
        }

        Map<String, String> candidateLabel = firstRecordByInstance.collectEntries { String rawInstance, Map record ->
            String bareName = bareSuiteName(rawInstance)
            String browser = browserByInstance[rawInstance]
            [(rawInstance): (browser ? "${bareName} (${browser})" : bareName)]
        }

        Map<String, List<String>> instancesByCandidate = firstRecordByInstance.keySet().groupBy { candidateLabel[it] }

        Map<String, String> labelByInstance = [:]
        instancesByCandidate.each { String candidate, List<String> rawInstances ->
            if (rawInstances.size() == 1) {
                labelByInstance[rawInstances[0]] = candidate
            } else {
                List<String> ordered = rawInstances.sort { firstRecordByInstance[it].start as Long }
                ordered.eachWithIndex { String rawInstance, int index ->
                    labelByInstance[rawInstance] = "${candidate} #${index + 1}"
                }
            }
        }

        records.each { Map record ->
            String rawInstance = (record.suiteInstance ?: record.suiteName ?: 'Suite') as String
            record.displayLabel = labelByInstance[rawInstance]
        }
    }

    /**
     * suiteInstance carries the full Test Suites folder path (e.g.
     * "TestSuitesFolder/FolderSuite"), its own per-occurrence timestamp
     * segment, and, on runs where Katalon adds one, its own disambiguation
     * hash suffix glued onto the folder name itself - all meaningful as the
     * internal grouping key, none meaningful to show. This reduces a raw
     * suiteInstance down to just the suite's own leaf name for display.
     * Order matters: the timestamp segment is stripped first, since a
     * timestamp like "20260818_080402" is also a valid hex string and would
     * otherwise get wrongly eaten by the hash-suffix pattern first, leaving
     * the date behind attached to the wrong side of a slash.
     */
    private static String bareSuiteName(String rawInstance) {
        String withoutTimestamp = rawInstance.replaceFirst(/\/\d{8}_\d{6}$/, '')
        String withoutHash = withoutTimestamp.replaceFirst(/_[0-9a-fA-F]{6,10}$/, '')
        int lastSlash = withoutHash.lastIndexOf('/')
        return lastSlash >= 0 ? withoutHash.substring(lastSlash + 1) : withoutHash
    }

    private static void replayTestCase(ExtentReports extent, Map record, File resultsDir, List<Map> categoryDefs) {
        String suiteLabel = (record.displayLabel ?: safeCall { bareSuiteName(record.suiteInstance as String) } ?: record.suiteName ?: 'Suite') as String
        String browser = record.browser as String
        ExtentTest test = extent.createTest("${suiteLabel} / ${record.name}")

        // ExtentTest itself has no public way to set a test's start/end
        // time - createTest() stamps "now" with no exposed override.
        // ExtentTest.getModel() is public and returns the live, mutable
        // model.Test object underneath, which does have real
        // setStartTime()/setEndTime(). setStartTime() is safe to call
        // immediately. setEndTime() is not safe to call yet: every
        // ExtentTest.log() call re-stamps the model's endTime to "now" as a
        // side effect, so it's set again, correctly this time, at the very
        // end of this method, after every log()/createNode() call for this
        // test has already happened.
        if (record.start) {
            test.getModel().setStartTime(new Date(record.start as Long))
        }

        // Extent's Spark report has no dedicated "Suites" concept -
        // assignAuthor() is repurposed instead: it renders its own
        // clickable, filterable tab (a genuine Author view, not a
        // workaround) that groups every test tagged with the same suite
        // label. assignDevice() gives browser its own separate, real
        // Device tab too, independent of what the Author label reads.
        test.assignAuthor(suiteLabel)
        if (browser) {
            test.assignDevice(browser)
        }

        List<String> categories = matchCategories(categoryDefs, record.status as String, record.message as String)
        if (categories) {
            test.assignCategory(categories as String[])
        }

        Status status = mapStatus(record.status as String)

        // Auto-captured steps (from Katalon's own execution log) take
        // priority as the test's step tree when present; manual
        // ExtentKeywords.step()/log() calls are appended as their own
        // entries afterwards rather than positionally interleaved - see
        // ARCHITECTURE.md for why a true interleave isn't attempted here.
        List autoSteps = record.autoSteps as List
        if (autoSteps) {
            autoSteps.each { addStepNode(test, it as Map) }
        }
        (record.manualEntries as List)?.each { Map entry ->
            Status entryStatus = mapManualStatus(entry.status as String)
            addLogEntry(test, entryStatus, entry.name as String, entry.details as String, entry.screenshotFile as String, resultsDir)
        }

        if (status == Status.FAIL && record.message) {
            addLogEntry(test, status, 'Failure details', record.message as String, null, resultsDir)
        }
        if (record.screenshotFile) {
            addLogEntry(test, status, status == Status.PASS ? 'Screenshot' : 'Screenshot on failure', null, record.screenshotFile as String, resultsDir)
        }

        test.log(status, "Katalon status: ${record.status}")

        // Must be the last thing done to this test - see the comment on
        // setStartTime() above for why: every log()/createNode() call
        // above already re-stamped the model's endTime to "now" as a side
        // effect, so this only sticks because nothing else touches this
        // test's model after this point.
        if (record.stop) {
            test.getModel().setEndTime(new Date(record.stop as Long))
        }
    }

    private static void addStepNode(ExtentTest parent, Map step) {
        ExtentTest node = parent.createNode(step.name as String)
        if (step.start) {
            node.getModel().setStartTime(new Date(step.start as Long))
        }
        Status status = mapKatalonStepStatus(step.status as String)
        if (step.message) {
            node.log(status, step.message as String)
        } else {
            node.log(status, (step.name ?: '') as String)
        }
        (step.children as List)?.each { addStepNode(node, it as Map) }
        // Last thing done to this node - see replayTestCase()'s identical
        // setEndTime() note for why: log() re-stamps endTime as a side
        // effect, so this has to come after it (and after any children,
        // to be safe) to actually stick.
        if (step.stop) {
            node.getModel().setEndTime(new Date(step.stop as Long))
        }
    }

    private static void addLogEntry(ExtentTest test, Status status, String name, String details, String screenshotFile, File resultsDir) {
        String text = details ?: (name ?: '')
        if (screenshotFile) {
            File img = new File(resultsDir, "attachments/${screenshotFile}")
            if (img.isFile()) {
                try {
                    String base64 = Base64.encoder.encodeToString(Files.readAllBytes(img.toPath()))
                    test.log(status, text, MediaEntityBuilder.createScreenCaptureFromBase64String(base64, name).build())
                    return
                } catch (Throwable ignored) { }
            }
        }
        test.log(status, text)
    }

    private static void writeSystemInfo(ExtentReports extent, List<Map> records) {
        extent.setSystemInfo('Project', safe(RunConfiguration.getProjectName()))
        extent.setSystemInfo('Execution Profile', safe(RunConfiguration.getExecutionProfile()))
        extent.setSystemInfo('Katalon Studio Version', safe(RunConfiguration.getAppVersion()))
        extent.setSystemInfo('OS', safe(RunConfiguration.getOS()))
        extent.setSystemInfo('Host', safe(RunConfiguration.getHostName()))
        extent.setSystemInfo('Java Version', safe(System.getProperty('java.version')))
        List<String> browsers = records.collect { it.browser as String }.findAll { it }.unique()
        if (browsers) {
            extent.setSystemInfo('Browser', browsers.join(', '))
        }
        Map ci = detectCI()
        extent.setSystemInfo('Executor', ci.name as String)
        if (ci.buildUrl) {
            extent.setSystemInfo('Build URL', ci.buildUrl as String)
        }
    }

    // ------------------------------------------------------------------
    // Categories - Katalon/Selenium-specific failure classification,
    // matched here in plain Groovy and applied via Extent's own tag
    // system (assignCategory).
    // ------------------------------------------------------------------

    private static List<Map> loadCategoryDefinitions() {
        try {
            File src = ExtentConfig.getCategoriesFile()
            if (!src.exists()) {
                return []
            }
            return new JsonSlurper().parse(src) as List<Map>
        } catch (Throwable t) {
            logger.logWarning("[Extent] Could not read categories file: ${t.getMessage()}")
            return []
        }
    }

    private static List<String> matchCategories(List<Map> categoryDefs, String status, String message) {
        List<String> matches = []
        categoryDefs.each { Map category ->
            List<String> statuses = (category.matchedStatuses as List)?.collect { it.toString().toUpperCase() } ?: []
            if (statuses && !statuses.contains(status)) {
                return
            }
            String messageRegex = category.messageRegex as String
            String traceRegex = category.traceRegex as String
            boolean matches1 = !messageRegex || (message && Pattern.compile(messageRegex, Pattern.DOTALL).matcher(message).matches())
            boolean matches2 = !traceRegex || (message && Pattern.compile(traceRegex, Pattern.DOTALL).matcher(message).matches())
            if ((messageRegex || traceRegex) ? (matches1 && matches2) : true) {
                matches << (category.name as String)
            }
        }
        return matches
    }

    // ------------------------------------------------------------------
    // Screenshots
    // ------------------------------------------------------------------

    private static String captureScreenshot(File resultsDir, String uuid) {
        try {
            WebDriver driver = DriverFactory.getWebDriver()
            if (driver instanceof TakesScreenshot) {
                byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES)
                String fileName = "${uuid}.png"
                new File(resultsDir, "attachments/${fileName}").bytes = bytes
                return fileName
            }
        } catch (Throwable ignored) {
            // No active WebUI driver (API/mobile-only test, or driver already closed) - skip silently
        }
        return null
    }

    // ------------------------------------------------------------------
    // Step capture - Katalon keeps a test case's execution log open until
    // every AfterTestCase listener registered for it (not just this one)
    // has returned, so parsing is deferred to this suite's own
    // AfterTestSuite, and every currently-pending entry (not just this
    // suite's own) is processed there, since whichever suite's
    // AfterTestSuite runs first ends up doing the work for others too
    // when suites run concurrently.
    // ------------------------------------------------------------------

    private static void queuePendingSteps(File resultsDir, String uuid, String testCaseId) {
        try {
            String logFolder = safeCall { RunConfiguration.getReportFolder() }
            if (!logFolder) {
                return
            }
            withRunLock(resultsDir) {
                new File(resultsDir, '.extent-pending-steps.txt').append("${uuid}|${testCaseId}|${logFolder}\n")
            }
        } catch (Throwable ignored) { }
    }

    private static void processPendingSteps(File resultsDir, boolean isFinalSuite) {
        List<String> claimedLines = withRunLock(resultsDir) {
            File pendingFile = new File(resultsDir, '.extent-pending-steps.txt')
            if (!pendingFile.isFile()) {
                return []
            }
            List<String> lines = pendingFile.readLines()
            pendingFile.delete()
            return lines
        } as List<String>

        if (!claimedLines) {
            return
        }
        List<String> stillPending = []
        claimedLines.each { line ->
            if (!line?.trim()) {
                return
            }
            String[] parts = line.split('\\|', 3)
            if (parts.length != 3) {
                return
            }
            String uuid = parts[0]
            String testCaseId = parts[1]
            String logFolder = parts[2]
            try {
                if (!new File(logFolder).isDirectory()) {
                    return
                }
                Map parsed = parseWithRetry(logFolder, testCaseId)
                if (parsed) {
                    patchRecordWithSteps(resultsDir, uuid, parsed)
                } else {
                    stillPending << line
                }
            } catch (Throwable ignored) {
                stillPending << line
            }
        }

        if (!stillPending) {
            return
        }
        if (isFinalSuite) {
            retryPendingStepsSynchronously(resultsDir, stillPending)
        } else {
            withRunLock(resultsDir) {
                new File(resultsDir, '.extent-pending-steps.txt').append(stillPending.collect { "${it}\n" }.join(''))
            }
        }
    }

    /**
     * Last resort for the final suite's own entries, which have no later
     * suite left to hand an unparsed entry to. Blocks rather than using a
     * background thread: a CI pipeline running one suite per process exits
     * almost immediately after this listener returns, which would kill a
     * background thread before it finished.
     */
    private static void retryPendingStepsSynchronously(File resultsDir, List<String> pendingLines) {
        pendingLines.each { line ->
            String[] parts = line.split('\\|', 3)
            if (parts.length != 3) {
                return
            }
            String uuid = parts[0]
            String testCaseId = parts[1]
            String logFolder = parts[2]
            Map parsed = null
            for (int attempt = 1; attempt <= 20 && !parsed; attempt++) {
                try {
                    parsed = parseStepsFromExecutionLog(logFolder, testCaseId)
                } catch (Throwable ignored) { }
                if (!parsed && attempt < 20) {
                    Thread.sleep(1000)
                }
            }
            if (parsed) {
                patchRecordWithSteps(resultsDir, uuid, parsed)
            }
        }
    }

    private static Map parseWithRetry(String logFolder, String testCaseId) {
        int maxAttempts = 5
        Map parsed = null
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                parsed = parseStepsFromExecutionLog(logFolder, testCaseId)
            } catch (Throwable ignored) { }
            if (parsed) {
                return parsed
            }
            if (attempt < maxAttempts) {
                Thread.sleep(300)
            }
        }
        return parsed
    }

    /**
     * Patches both the step tree AND the real start/stop timestamps into
     * an already-written record, replacing finishTestCase()'s rough
     * same-process estimate with the timing Katalon's own execution log
     * actually recorded for "Start Test Case"/"End Test Case" - the same
     * authoritative source Katalon's own reports use.
     */
    private static void patchRecordWithSteps(File resultsDir, String uuid, Map parsed) {
        withRunLock(resultsDir) {
            File recordFile = new File(resultsDir, "${uuid}.json")
            if (!recordFile.isFile()) {
                return
            }
            Map record = new JsonSlurper().parse(recordFile) as Map
            record.autoSteps = parsed.steps
            if (parsed.start) {
                record.start = parsed.start
            }
            if (parsed.stop) {
                record.stop = parsed.stop
            }
            recordFile.text = new JsonBuilder(record).toPrettyString()
        }
    }

    /** Uses Katalon's own XML log parser rather than a hand-rolled one - it already handles control-character cleanup a strict parser would choke on. */
    @SuppressWarnings('deprecation')
    private static Map parseStepsFromExecutionLog(String logFolder, String testCaseId) {
        TestSuiteLogRecord suiteRecord = new TestSuiteXMLLogParser().readTestSuiteLogFromXMLFiles(logFolder, new org.eclipse.core.runtime.NullProgressMonitor())
        List<TestCaseLogRecord> testCases = suiteRecord.getAllTestCaseLogRecords() ?: []
        TestCaseLogRecord match = testCases.find { matchesTestCaseId(it.getName(), testCaseId) }
        if (match == null) {
            return null
        }
        List<Map> steps = convertLogRecordsToSteps(match.getChildRecords())
        if (!steps) {
            return null
        }
        return [
            steps: steps,
            start: match.getStartTime(),
            stop : match.getEndTime() > 0 ? match.getEndTime() : match.getStartTime(),
        ]
    }

    private static boolean matchesTestCaseId(String recordName, String testCaseId) {
        if (!recordName || !testCaseId) {
            return false
        }
        return recordName == testCaseId || recordName.endsWith(testCaseId) || testCaseId.endsWith(recordName)
    }

    @SuppressWarnings('deprecation')
    private static List<Map> convertLogRecordsToSteps(ILogRecord[] records) {
        List<Map> result = []
        if (!records) {
            return result
        }
        records.each { ILogRecord rec ->
            if (!(rec instanceof TestStepLogRecord)) {
                return
            }
            String name = rec.getName()
            if (isInternalStepName(name)) {
                return
            }
            TestStatus.TestStatusValue statusValue = rec.getStatus()?.getStatusValue()
            result << [
                name    : name,
                status  : (statusValue?.toString() ?: 'PASSED'),
                message : (statusValue?.isError() && rec.getMessage()) ? rec.getMessage() : null,
                start   : rec.getStartTime(),
                stop    : rec.getEndTime() > 0 ? rec.getEndTime() : rec.getStartTime(),
                children: convertLogRecordsToSteps(rec.getChildRecords()),
            ]
        }
        return result
    }

    private static boolean isInternalStepName(String stepName) {
        return stepName != null && (stepName.contains('ExtentReportBridge.') || stepName.contains('ExtentKeywords.'))
    }

    // ------------------------------------------------------------------
    // Status mapping - Extent's Status enum has five levels
    // (INFO/PASS/WARNING/SKIP/FAIL) with no separate "broken" tier for a
    // test that errored rather than failed an assertion, so Katalon's
    // ERROR/INCOMPLETE both map to FAIL; the original Katalon status is
    // still visible in the logged detail text.
    // ------------------------------------------------------------------

    private static Status mapStatus(String katalonStatus) {
        switch (katalonStatus?.toUpperCase()) {
            case 'PASSED': return Status.PASS
            case 'FAILED': return Status.FAIL
            case 'SKIPPED': return Status.SKIP
            case 'ERROR':
            case 'INCOMPLETE': return Status.FAIL
            default: return Status.FAIL
        }
    }

    private static Status mapKatalonStepStatus(String katalonStepStatus) {
        switch (katalonStepStatus?.toUpperCase()) {
            case 'PASSED': return Status.PASS
            case 'FAILED': return Status.FAIL
            case 'SKIPPED': return Status.SKIP
            case 'ERROR':
            case 'INCOMPLETE': return Status.FAIL
            default: return Status.INFO
        }
    }

    private static Status mapManualStatus(String value) {
        switch (value?.toUpperCase()) {
            case 'PASS': return Status.PASS
            case 'FAIL': return Status.FAIL
            case 'SKIP': return Status.SKIP
            case 'WARNING': return Status.WARNING
            default: return Status.INFO
        }
    }

    // ------------------------------------------------------------------
    // Cross-process coordination, run/collection detection - a real file
    // lock, not just a 'synchronized' block, since a Test Suite Collection
    // can run member suites in genuinely separate OS processes at the
    // same time.
    // ------------------------------------------------------------------

    private static Object withRunLock(File resultsDir, Closure action) {
        synchronized (INTRA_JVM_LOCK) {
            RandomAccessFile raf = new RandomAccessFile(new File(resultsDir, '.extent-run.lock'), 'rw')
            try {
                FileLock lock = raf.getChannel().lock()
                try {
                    return action.call()
                } finally {
                    lock.release()
                }
            } finally {
                raf.close()
            }
        }
    }

    private static Map readRunMarker(File resultsDir) {
        File markerFile = new File(resultsDir, '.extent-run-marker.txt')
        if (!markerFile.isFile()) {
            return [runDir: '', reportPath: '']
        }
        try {
            List<String> lines = markerFile.readLines()
            return [runDir: lines.size() > 0 ? lines[0] : '', reportPath: lines.size() > 1 ? lines[1] : '']
        } catch (Throwable ignored) {
            return [runDir: '', reportPath: '']
        }
    }

    private static void writeRunMarker(File resultsDir, String runDir, String reportPath) {
        try {
            new File(resultsDir, '.extent-run-marker.txt').text = "${runDir ?: ''}\n${reportPath ?: ''}\n"
        } catch (Throwable ignored) { }
    }

    /**
     * Deletes this run's predecessor intermediate records (*.json and
     * attachments/) from an earlier, unrelated run - never touches
     * .extent-run-marker.txt or .extent-collection-progress.txt, which are
     * reset separately right where this is called from.
     */
    private static void clearPreviousResults(File resultsDir) {
        resultsDir.listFiles({ File f -> f.isFile() && f.name.endsWith('.json') } as FileFilter)?.each { it.delete() }
        File attachments = new File(resultsDir, 'attachments')
        if (attachments.isDirectory()) {
            deleteRecursively(attachments)
            attachments.mkdirs()
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return
        }
        if (f.isDirectory()) {
            f.listFiles()?.each { deleteRecursively(it) }
        }
        f.delete()
    }

    /**
     * Whether report generation should actually run now, or whether other
     * sub-suites in the same run are still going - count sub-suites
     * planned for this run from Reports/<run-ts>/plan.jsonl (written
     * before any sub-suite starts), and compare against how many distinct
     * suite instances have reported themselves complete so far. Falls back
     * to true (generate every time) whenever either signal can't be
     * determined - correct but less efficient, never the other way around.
     */
    private static boolean shouldGenerateReportNow(File resultsDir, File runDir) {
        if (runDir == null) {
            return true
        }
        Integer expected = expectedSuiteCountForRun(runDir)
        String key = suiteInstanceKey(runDir)
        if (expected == null || key == null) {
            return true
        }
        return withRunLock(resultsDir) {
            File progressFile = new File(resultsDir, '.extent-collection-progress.txt')
            Set<String> completed = progressFile.isFile() ?
                (progressFile.readLines().findAll { it?.trim() } as Set) : ([] as Set)
            completed << key
            progressFile.text = completed.join('\n') + '\n'
            return completed.size() >= expected
        } as boolean
    }

    private static Integer expectedSuiteCountForRun(File runDir) {
        try {
            File planFile = new File(runDir, 'plan.jsonl')
            if (!planFile.isFile()) {
                return null
            }
            String firstLine
            planFile.withReader('UTF-8') { reader -> firstLine = reader.readLine() }
            if (!firstLine?.trim()) {
                return null
            }
            Map root = new JsonSlurper().parseText(firstLine) as Map
            Map execution = root.execution as Map
            String kind = execution?.kind
            if (kind == 'TEST_SUITE_COLLECTION') {
                int count = ((execution.children as List) ?: []).count { (it as Map).kind == 'TEST_SUITE' }
                return count > 0 ? count : null
            }
            if (kind == 'TEST_SUITE' || kind == 'TEST_CASE') {
                return 1
            }
            return null
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * Keeps the trailing per-suite-run timestamp segment as part of a
     * suite occurrence's identity, rather than stopping before it - both
     * of this function's callers (finishTestCase()'s record.suiteInstance,
     * and shouldGenerateReportNow()'s completed-suites Set) need to tell
     * two occurrences of the same suite name apart, not collapse them.
     *
     * Katalon does not reliably add a disambiguating hash suffix to the
     * report folder NAME for a repeated suite in console-mode execution -
     * two occurrences of the same suite can both write to
     * ".../<suite name>/<own timestamp>", identical folder name, distinct
     * only by the timestamp subfolder. Keeping that segment (rather than
     * stopping before it, as an opaque folder-name suffix alone would
     * assume) is what actually tells two such occurrences apart.
     */
    private static String suiteInstanceKey(File runDir) {
        List<String> segments = suiteInstancePathSegments(runDir)
        if (segments == null) {
            return null
        }
        int timestampIndex = segments.findIndexOf { it ==~ /\d{8}_\d{6}/ }
        return timestampIndex > 0 ? segments[0..timestampIndex].join('/') : null
    }

    private static List<String> suiteInstancePathSegments(File runDir) {
        try {
            String reportFolder = RunConfiguration.getReportFolder()
            if (!reportFolder?.trim()) {
                return null
            }
            String runDirCanonical = runDir.canonicalPath
            String reportFolderCanonical = new File(reportFolder).canonicalPath
            if (!reportFolderCanonical.startsWith(runDirCanonical + File.separator)) {
                return null
            }
            String relative = reportFolderCanonical.substring(runDirCanonical.length() + 1).replace('\\', '/')
            return relative.split('/') as List
        } catch (Throwable ignored) {
            return null
        }
    }

    private static File currentRunDir() {
        try {
            String reportFolder = safeCall { RunConfiguration.getReportFolder() }
            if (!reportFolder?.trim()) {
                return null
            }
            File cursor = new File(reportFolder)
            int guard = 0
            while (cursor != null && !new File(cursor, 'plan.jsonl').isFile() && guard++ < 20) {
                cursor = cursor.parentFile
            }
            if (cursor != null && new File(cursor, 'plan.jsonl').isFile()) {
                return cursor
            }
            return runRootFromTimestampFolders(new File(reportFolder))
        } catch (Throwable ignored) {
            return null
        }
    }

    private static File runRootFromTimestampFolders(File reportFolder) {
        File cursor = reportFolder
        boolean pastOwnTimestamp = false
        int guard = 0
        while (cursor != null && guard++ < 20) {
            if (cursor.name ==~ /\d{8}_\d{6}/) {
                if (pastOwnTimestamp) {
                    return cursor
                }
                pastOwnTimestamp = true
            }
            cursor = cursor.parentFile
        }
        return null
    }

    /**
     * RunConfiguration.getExecutionSourceName() only ever returns the
     * individual member suite's own name for a Test Suite Collection -
     * there is no public API that hands a Test Listener the enclosing
     * collection's name. Recovered instead from the run's own folder
     * structure: Katalon writes a sibling directory directly under the
     * run's report root, named after the collection itself, alongside
     * each member suite's own folder.
     */
    private static String resolveCollectionName(File targetRunDir, String ownSuiteName) {
        if (targetRunDir == null) {
            return null
        }
        String viaMetadata = resolveCollectionNameFromMetadata(targetRunDir, ownSuiteName)
        return viaMetadata ?: resolveCollectionNameFromSiblingFolder(targetRunDir, ownSuiteName)
    }

    private static String resolveCollectionNameFromMetadata(File targetRunDir, String ownSuiteName) {
        File metadataDir = new File(targetRunDir, '.metadata')
        if (!new File(metadataDir, '.collection').isFile()) {
            return null
        }
        File cursor = metadataDir
        int guard = 0
        while (guard++ < 20) {
            File[] entries = cursor.listFiles({ File f -> f.isDirectory() } as FileFilter)
            if (!entries || entries.length == 0) {
                return null
            }
            File chosen = entries.find { it.name != ownSuiteName } ?: entries[0]
            File[] deeper = chosen.listFiles({ File f -> f.isDirectory() } as FileFilter)
            if (!deeper || deeper.length == 0) {
                return chosen.name
            }
            cursor = chosen
        }
        return null
    }

    private static String resolveCollectionNameFromSiblingFolder(File targetRunDir, String ownSuiteName) {
        File[] siblingDirs = targetRunDir.listFiles({ File f -> f.isDirectory() } as FileFilter)
        if (!siblingDirs) {
            return null
        }
        List<File> candidates = siblingDirs.findAll { it.name != ownSuiteName && it.name != 'requests' && it.name != '.metadata' }
        String runRootName = targetRunDir.name
        List<String> matches = candidates.collect { descendToCollectionFolder(it, runRootName, 0) }.findAll { it != null }
        return matches.size() == 1 ? matches[0] : null
    }

    private static String descendToCollectionFolder(File dir, String runRootName, int depth) {
        if (depth > 20) {
            return null
        }
        File matchingSubDir = new File(dir, runRootName)
        if (matchingSubDir.isDirectory() && !new File(matchingSubDir, 'execution0.log').isFile()) {
            return dir.name
        }
        File[] children = dir.listFiles({ File f -> f.isDirectory() } as FileFilter)
        if (!children) {
            return null
        }
        List<String> matches = children.collect { descendToCollectionFolder(it, runRootName, depth + 1) }.findAll { it != null }
        return matches.size() == 1 ? matches[0] : null
    }

    // ------------------------------------------------------------------
    // Suite naming, CI detection, misc helpers
    // ------------------------------------------------------------------

    private static String resolveSuiteNameQuiet() {
        String executionSourceName = safeCall { RunConfiguration.getExecutionSourceName() }
        if (executionSourceName?.trim()) {
            return executionSourceName
        }
        String executionSource = safeCall { RunConfiguration.getExecutionSource() }
        if (executionSource?.trim()) {
            return readableName(executionSource.replace('\\', '/')).replaceAll(/(?i)\.(ts|tc|tsc)$/, '')
        }
        return null
    }

    private static String resolveSuiteName(TestSuiteContext testSuiteContext) {
        String testSuiteId = safeCall { testSuiteContext?.getTestSuiteId() }
        String executionSourceName = safeCall { RunConfiguration.getExecutionSourceName() }
        String executionSource = safeCall { RunConfiguration.getExecutionSource() }

        String resolved
        if (executionSourceName?.trim()) {
            resolved = executionSourceName
        } else if (executionSource?.trim()) {
            resolved = readableName(executionSource.replace('\\', '/')).replaceAll(/(?i)\.(ts|tc|tsc)$/, '')
        } else if (testSuiteId?.trim()) {
            resolved = readableName(testSuiteId)
        } else {
            resolved = 'Suite'
        }

        String collectionName = safeCall { resolveCollectionName(currentRunDir(), resolved) }
        if (collectionName?.trim() && collectionName != resolved) {
            resolved = collectionName
        }
        return resolved
    }

    private static String detectActiveBrowser() {
        try {
            return DriverFactory.getWebDriver() != null ? DriverFactory.getExecutedBrowser()?.toString() : null
        } catch (Throwable ignored) {
            return null
        }
    }

    private static Map detectCI() {
        Map<String, String> e = System.getenv()
        if (e['JENKINS_URL']) {
            return [name: 'Jenkins', buildUrl: e['BUILD_URL']]
        }
        if (e['TF_BUILD'] || e['SYSTEM_TEAMFOUNDATIONCOLLECTIONURI']) {
            String collectionUri = e['SYSTEM_TEAMFOUNDATIONCOLLECTIONURI'] ?: ''
            String project = e['SYSTEM_TEAMPROJECT'] ?: ''
            String buildId = e['BUILD_BUILDID'] ?: ''
            String buildUrl = (collectionUri && project && buildId) ?
                "${collectionUri}${project}/_build/results?buildId=${buildId}" : ''
            return [name: 'Azure Pipelines', buildUrl: buildUrl]
        }
        if (e['GITHUB_ACTIONS']) {
            String serverUrl = e['GITHUB_SERVER_URL'] ?: 'https://github.com'
            String repo = e['GITHUB_REPOSITORY'] ?: ''
            String runId = e['GITHUB_RUN_ID'] ?: ''
            return [name: 'GitHub Actions', buildUrl: "${serverUrl}/${repo}/actions/runs/${runId}"]
        }
        if (e['GITLAB_CI']) {
            return [name: 'GitLab CI', buildUrl: e['CI_JOB_URL']]
        }
        return [name: 'Local Run', buildUrl: null]
    }

    private static String safeCall(Closure<String> supplier) {
        try {
            return supplier.call()
        } catch (Throwable ignored) {
            return null
        }
    }

    private static String readableName(String id) {
        if (!id) {
            return 'Unknown'
        }
        String[] parts = id.split('/')
        return parts[parts.length - 1]
    }

    private static String safe(String value) {
        return (value == null || value.trim().isEmpty()) ? 'N/A' : value
    }

    private static String sanitizeForFilename(String name) {
        return (name ?: 'Suite').replaceAll('[^a-zA-Z0-9 _-]', '_').trim()
    }
}
