package extent

import com.kms.katalon.core.configuration.RunConfiguration

/**
 * Reads Include/config/extent/extent.properties, with every key overridable
 * via an EXTENT_<KEY_IN_UPPER_SNAKE_CASE> environment variable so CI systems
 * can redirect output without editing files checked into the repo.
 */
class ExtentConfig {

    private static final String CONFIG_RELATIVE_PATH = 'Include/config/extent/extent.properties'

    private static final String DEFAULT_CATEGORIES_RELATIVE_PATH = 'Include/config/extent/categories.json'

    private static final String DEFAULT_RESULTS_DIR_NAME = 'extent-results'

    private static final String DEFAULT_REPORT_DIR_NAME = 'extent-report'

    private static Properties fileProps

    private static synchronized Properties fileProperties() {
        if (fileProps == null) {
            fileProps = new Properties()
            File configFile = new File(RunConfiguration.getProjectDir(), CONFIG_RELATIVE_PATH)
            if (configFile.exists()) {
                configFile.withInputStream { stream -> fileProps.load(stream) }
            }
        }
        return fileProps
    }

    private static String read(String key, String defaultValue) {
        // Every key here already starts with "extent." (e.g.
        // "extent.results.dir"), so upper-casing and swapping dots for
        // underscores alone already produces "EXTENT_RESULTS_DIR" - no
        // extra "EXTENT_" prefix needed on top of that.
        String envKey = key.toUpperCase().replace('.', '_')
        String envValue = System.getenv(envKey)
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue.trim()
        }
        return fileProperties().getProperty(key, defaultValue)
    }

    private static File resolvePath(String configuredPath) {
        File file = new File(configuredPath)
        return file.isAbsolute() ? file : new File(RunConfiguration.getProjectDir(), configuredPath)
    }

    static boolean isEnabled() {
        return Boolean.parseBoolean(read('extent.enabled', 'true'))
    }

    static File getResultsDir() {
        return resolvePath(read('extent.results.dir', DEFAULT_RESULTS_DIR_NAME))
    }

    static boolean cleanResultsBeforeRun() {
        return Boolean.parseBoolean(read('extent.clean.results.before.run', 'true'))
    }

    static boolean attachScreenshotOnFailure() {
        return Boolean.parseBoolean(read('extent.attach.screenshot.on.failure', 'true'))
    }

    static boolean attachScreenshotAlways() {
        return Boolean.parseBoolean(read('extent.attach.screenshot.always', 'false'))
    }

    static File getCategoriesFile() {
        return resolvePath(read('extent.categories.file', DEFAULT_CATEGORIES_RELATIVE_PATH))
    }

    /** Whether to assemble the Spark HTML report automatically at the end of the run - see ExtentReportBridge.generateHtmlReport(). */
    static boolean autoGenerateReport() {
        return Boolean.parseBoolean(read('extent.auto.generate.report', 'true'))
    }

    static File getReportDir() {
        return resolvePath(read('extent.report.dir', DEFAULT_REPORT_DIR_NAME))
    }

    /** "standard" or "dark" - see com.aventstack.extentreports.reporter.configuration.Theme. Falls back to "standard" for anything else. */
    static String getReportTheme() {
        return read('extent.report.theme', 'standard')?.trim()?.toLowerCase()
    }

    /**
     * Whether to automatically turn each test case's own Katalon execution
     * log into nested Extent log steps - no test script changes needed.
     * Reads an already-written, already-closed log file per test case, so
     * this can never affect the actual test outcome; turn off for very
     * large/slow suites if the extra per-test-case parsing overhead isn't
     * worth it, or if you only want the manual ExtentKeywords.step() calls
     * you've explicitly added.
     */
    static boolean captureSteps() {
        return Boolean.parseBoolean(read('extent.capture.steps', 'true'))
    }
}
