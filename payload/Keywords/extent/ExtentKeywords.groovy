package extent

import com.kms.katalon.core.annotation.Keyword

/**
 * Optional, opt-in enrichment API for Extent reporting from inside a Test
 * Case script or Cucumber glue code. The ExtentTestListener already gives
 * every test case a report entry with zero code changes; call these only
 * where you want finer detail (nested steps, extra screenshots,
 * categorization).
 *
 * Deliberately a small, opt-in API: step() for nested detail,
 * info/warning/pass/fail() for a manual log line, and attachScreenshot()
 * for an extra capture. Failure categorization is automatic instead (see
 * Include/config/extent/categories.json) rather than a manual keyword.
 *
 * Usage from a Test Case script:
 *   CustomKeywords.'extent.ExtentKeywords.step'('Log in as admin', {
 *       WebUI.setText(findTestObject('Page/input_Username'), 'admin')
 *       WebUI.setText(findTestObject('Page/input_Password'), 'pwd')
 *       WebUI.click(findTestObject('Page/button_Login'))
 *   })
 */
class ExtentKeywords {

    @Keyword
    static void step(String stepName, Closure body) {
        ExtentReportBridge.step(stepName, body)
    }

    @Keyword
    static void info(String message) {
        ExtentReportBridge.logManual('INFO', message, message)
    }

    @Keyword
    static void warning(String message) {
        ExtentReportBridge.logManual('WARNING', message, message)
    }

    @Keyword
    static void pass(String message) {
        ExtentReportBridge.logManual('PASS', message, message)
    }

    @Keyword
    static void fail(String message) {
        ExtentReportBridge.logManual('FAIL', message, message)
    }

    /** Captures a screenshot of the active WebUI browser session, if any. No-op otherwise. */
    @Keyword
    static void attachScreenshot(String name = 'Screenshot') {
        ExtentReportBridge.attachScreenshot(name)
    }
}
