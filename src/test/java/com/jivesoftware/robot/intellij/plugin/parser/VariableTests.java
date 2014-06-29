package com.jivesoftware.robot.intellij.plugin.parser;

import com.jivesoftware.robot.intellij.plugin.lang.RobotPsiFile;
import com.jivesoftware.robot.intellij.plugin.psi.RobotKeyword;
import com.jivesoftware.robot.intellij.plugin.psi.RobotVariablesLine;
import org.junit.Test;

/**
 * Created by charles on 6/29/14.
 */
public class VariableTests extends RobotParserTest {
    private static final String VALID_VARIABLE_NAMES =
            "*** Variables ***\n" +
            "${camelCaseVar}=   Variable surrounded by spaces\n" +
            "${ ABC }=   Variable surrounded by spaces\n" +
            "${___}=     Underscore variable\n" +
            "${123}=     Number variable\n" +
            "${ A Variable Name }=  Variable name with spaces\n" +
            "@{ARRAY}=   Array variable\n" +
            "*** Test Cases ***\n" +
            "My Test Case With Many Variables\n" +
            "  Foo Keyword  ${camelCaseVar}  ${ ABC }  ${___}  ${123}  ${ A Variable Name }\n" +
            "  Bar Keyword  @{ARRAY}\n";

    @Test
    public void testValidVariableNames() {
        RobotPsiFile file = doTestParseSucceeds(VALID_VARIABLE_NAMES);
        assertFileHasPsiElements(file, RobotVariablesLine.class, 6);
        assertFileHasPsiElements(file, RobotKeyword.class, 2);
    }
}
