import com.kms.katalon.core.annotation.AfterTestCase
import com.kms.katalon.core.annotation.AfterTestSuite
import com.kms.katalon.core.annotation.BeforeTestCase
import com.kms.katalon.core.annotation.BeforeTestSuite
import com.kms.katalon.core.context.TestCaseContext
import com.kms.katalon.core.context.TestSuiteContext

import extent.ExtentReportBridge

/**
 * Drop-in listener that turns every Katalon test suite run into an Extent
 * report - zero changes required in existing Test Cases or Test Suites.
 *
 * Katalon auto-discovers every class under "Test Listeners" and invokes
 * annotated methods at the documented points in the run:
 *   BeforeTestSuite -> [BeforeTestCase -> test case body -> AfterTestCase]* -> AfterTestSuite
 *
 * All actual work is delegated to extent.ExtentReportBridge, which never
 * throws: a bug in report generation must never fail or change the result
 * of the real test.
 */
class ExtentTestListener {

    @BeforeTestSuite
    def beforeTestSuite(TestSuiteContext testSuiteContext) {
        ExtentReportBridge.startSuite(testSuiteContext)
    }

    @BeforeTestCase
    def beforeTestCase(TestCaseContext testCaseContext) {
        ExtentReportBridge.startTestCase(testCaseContext)
    }

    @AfterTestCase
    def afterTestCase(TestCaseContext testCaseContext) {
        ExtentReportBridge.finishTestCase(testCaseContext)
    }

    @AfterTestSuite
    def afterTestSuite(TestSuiteContext testSuiteContext) {
        ExtentReportBridge.finishSuite(testSuiteContext)
    }
}
