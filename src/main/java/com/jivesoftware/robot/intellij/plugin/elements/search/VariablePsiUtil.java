package com.jivesoftware.robot.intellij.plugin.elements.search;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.util.PsiTreeUtil;
import com.jivesoftware.robot.intellij.plugin.elements.references.RobotFileReference;
import com.jivesoftware.robot.intellij.plugin.elements.references.RobotVariableReference;
import com.jivesoftware.robot.intellij.plugin.elements.stubindex.indexes.RobotAssignmentNormalizedNameIndex;
import com.jivesoftware.robot.intellij.plugin.elements.stubindex.indexes.RobotKeywordTitleNormalizedNameIndex;
import com.jivesoftware.robot.intellij.plugin.elements.stubindex.indexes.RobotVariableNormalizedNameIndex;
import com.jivesoftware.robot.intellij.plugin.lang.RobotPsiFile;
import com.jivesoftware.robot.intellij.plugin.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by charles on 7/13/14.
 */
public class VariablePsiUtil {
    public static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{ ?([^\\{\\}]+) ?\\}");

    public static Optional<String> getVariableName(@NotNull PsiElement element) {
        String text = element.getText();
        return getVariableName(text);
    }

    public static Optional<String> getVariableName(String codeText) {
        Matcher matcher = VARIABLE_PATTERN.matcher(codeText);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.absent();
    }

    public static Map<String, VariableInfo> getVariableEnvironment(@NotNull RobotPsiFile file) {
        return getVariableEnvironment(file, Maps.<String, VariableInfo>newHashMap(), Sets.<String>newHashSet());
    }


    private static Map<String, VariableInfo> getVariableEnvironment(@NotNull RobotPsiFile file, Map<String, VariableInfo> env, Set<String> searchedFiles) {
        final VirtualFile currentVirtualFile = file.getVirtualFile();
        final String currentCanonicalPath = currentVirtualFile != null ? currentVirtualFile.getCanonicalPath() : null;
        // To avoid infinite loops if the Robot file includes itself, or there's a loop in Resource file inclusions
        if (currentCanonicalPath != null && searchedFiles.contains(currentCanonicalPath)) {
            return env;
        }
        searchedFiles.add(currentCanonicalPath);
        env = getVariableEnvironmentCurrentFile(file, env);

        RobotTable[] tables = file.findChildrenByClass(RobotTable.class);
        for (RobotTable table: tables) {
            if (table.getSettingsTable() == null) {
                continue;
            }
            RobotSettingsTable settingsTable = table.getSettingsTable();
            Collection<RobotResourceSetting> resourceSettings = PsiTreeUtil.findChildrenOfType(settingsTable, RobotResourceSetting.class);
            for (RobotResourceSetting resourceSetting : resourceSettings) {
                RobotResourceFile robotResourceFile = resourceSetting.getResourceFile();
                RobotFileReference robotFileReference = new RobotFileReference(robotResourceFile);
                PsiElement resourceFile = robotFileReference.resolve(env);
                if (resourceFile instanceof RobotPsiFile) {
                    RobotPsiFile robotPsiFile = (RobotPsiFile) resourceFile;
                    Map<String, VariableInfo> resourceFileEnv = getVariableEnvironment(robotPsiFile, Maps.<String, VariableInfo>newHashMap(), searchedFiles);
                    env = combineMaps(env, resourceFileEnv);
                }
            }
        }
        return env;
    }

    private static Map<String, VariableInfo> getVariableEnvironmentCurrentFile(@NotNull RobotPsiFile file, Map<String, VariableInfo> env) {
        RobotTable[] tables = file.findChildrenByClass(RobotTable.class);
        for (RobotTable table: tables) {
            if (table.getVariablesTable() == null) {
                continue;
            }
            RobotVariablesTable robotVariablesTable = table.getVariablesTable();
            for (RobotVariablesLine line: robotVariablesTable.getVariablesLineList()) {
                if (line.getScalarAssignmentLhs() == null) {
                    continue;
                }
                RobotScalarAssignmentLhs lhs = line.getScalarAssignmentLhs();
                Optional<String> varNameOpt = getVariableName(lhs);
                if (!varNameOpt.isPresent()) {
                    continue;
                }

                String varName = varNameOpt.get();
                String normalVarName = RobotPsiUtil.normalizeKeywordForIndex(varName);

                String varValue = line.getAssignableInVariablesTbl() == null ? "" : line.getAssignableInVariablesTbl().getKeywordArg().getText();
                String actualValue = substitute(varValue, env);

                PsiElement varDefinition = lhs.getScalarAssignment() != null ? lhs.getScalarAssignment() : lhs.getScalarVariable();
                env.put(normalVarName, new VariableInfo(actualValue, varDefinition));
            }
        }
        return env;
    }

    public static String substitute(String rawStringValue, Map<String, VariableInfo> env) {
        String subValue = rawStringValue;
        Matcher matcher = VARIABLE_PATTERN.matcher(rawStringValue);
        while (matcher.find()) {
            String varName = matcher.group(1);
            String normalizedName = RobotPsiUtil.normalizeKeywordForIndex(varName);
            VariableInfo envValue = env.get(normalizedName);
            if (envValue != null) {
                subValue = subValue.replace(matcher.group(0), envValue.getValue());
            } else {
                // Special case for Robot -- ${EMPTY} is the empty string.
                if (normalizedName.equals("empty")) {
                    subValue = subValue.replace(matcher.group(0), "");
                }
            }
        }
        return subValue;
    }

    private static <T, V> Map<T, V> combineMaps(Map<T, V> primary, Map<T, V> secondary) {
        Map<T, V> combined = Maps.newHashMap(primary);
        for (T key: secondary.keySet()) {
            if (combined.get(key) == null) {
                combined.put(key, secondary.get(key));
            }
        }
        return combined;
    }

    //----------Helpers for finding the definition of a Variable from the point of use----------
    public static Optional<PsiElement> findFirstDefinitionOfVariable(RobotTestCase test, PsiElement variableUsage) {
        Optional<String> optVariableName = getVariableName(variableUsage);
        if (!optVariableName.isPresent()) {
            return Optional.absent();
        }
        final String normalName = RobotPsiUtil.normalizeKeywordForIndex(optVariableName.get());
        final TextRange usageTextRange = variableUsage.getTextRange();

        Collection<RobotScalarAssignmentLhs> assignments = PsiTreeUtil.findChildrenOfType(test, RobotScalarAssignmentLhs.class);
        for (RobotScalarAssignmentLhs assignment: assignments) {
            TextRange textRange = assignment.getTextRange();
            // Skip assignments after the usage. A variable can't be defined in a future line of code!
            if (textRange != null && usageTextRange != null && textRange.getStartOffset() >= usageTextRange.getStartOffset()) {
                if (textRange.equals(usageTextRange)) {
                    // If the usage is an assignment and there are no previous assignments of this variable,
                    // then don't look further for its definition since it is a definition!
                    return Optional.absent();
                }
                continue;
            }
            Optional<String> optVarName = getVariableName(assignment);
            if (!optVarName.isPresent()) {
                continue;
            }
            String foundNormalName = RobotPsiUtil.normalizeKeywordForIndex(optVarName.get());
            if (normalName.equals(foundNormalName)) {
                return Optional.<PsiElement>of(assignment);
            }
        }

        RobotPsiFile file = (RobotPsiFile) test.getContainingFile();
        Map<String, VariableInfo> env = getVariableEnvironment(file);
        VariableInfo foundVar = env.get(normalName);
        if (foundVar != null) {
            return Optional.of(foundVar.getDefinition());
        }
        return Optional.absent();
    }

    public static Optional<PsiElement> findFirstDefinitionOfVariable(RobotKeywordDefinition keywordDefinition, PsiElement variableUsage) {
        Optional<String> optVariableName = getVariableName(variableUsage);
        if (!optVariableName.isPresent()) {
            return Optional.absent();
        }
        final String normalName = RobotPsiUtil.normalizeKeywordForIndex(optVariableName.get());
        final TextRange usageTextRange = variableUsage.getTextRange();

        Collection<RobotScalarAssignmentLhs> assignments = PsiTreeUtil.findChildrenOfType(keywordDefinition, RobotScalarAssignmentLhs.class);
        Collection<RobotArgumentDef> arguments = PsiTreeUtil.findChildrenOfType(keywordDefinition, RobotArgumentDef.class);
        Collection<RobotScalarDefaultArgValue> defaultArgs = PsiTreeUtil.findChildrenOfType(keywordDefinition, RobotScalarDefaultArgValue.class);

        List<PsiElement> definitionCandidates = Lists.newArrayList();
        definitionCandidates.addAll(arguments);
        definitionCandidates.addAll(defaultArgs);
        definitionCandidates.addAll(assignments);

        for (PsiElement definitionCandidate: definitionCandidates) {
            TextRange textRange = definitionCandidate.getTextRange();
            // A variable can't be defined on a future line of code!
            if (textRange != null && usageTextRange != null && textRange.getStartOffset() >= usageTextRange.getStartOffset()) {
                if (textRange.equals(usageTextRange)) {
                    // If the usage is an assignment and there are no previous assignments of this variable,
                    // then don't look further for its definition since it is a definition!
                    return Optional.absent();
                }
                continue;
            }
            Optional<String> optVarName = getVariableName(definitionCandidate);
            if (!optVarName.isPresent()) {
                continue;
            }
            String foundNormalName = RobotPsiUtil.normalizeKeywordForIndex(optVarName.get());
            if (normalName.equals(foundNormalName)) {
                return Optional.of(definitionCandidate);
            }
        }

        RobotPsiFile file = (RobotPsiFile) keywordDefinition.getContainingFile();
        Map<String, VariableInfo> env = getVariableEnvironment(file);
        VariableInfo foundVar = env.get(normalName);
        if (foundVar != null) {
            return Optional.of(foundVar.getDefinition());
        }
        return Optional.absent();
    }

    //-----------------Find Usages---------------------
    public static List<PsiElement> findVariableUsages(PsiElement robotVariable) {
        Optional<String> optVariableName = getVariableName(robotVariable);

        if (!optVariableName.isPresent()) {
            return Lists.newArrayList();
        }

        final String normalName = RobotPsiUtil.normalizeKeywordForIndex(optVariableName.get());

        RobotTestCase test = PsiTreeUtil.getParentOfType(robotVariable, RobotTestCase.class);
        if (test != null) {
            return findLocalVariableUsages(test, normalName);
        }

        RobotKeywordDefinition keywordDefinition = PsiTreeUtil.getParentOfType(robotVariable, RobotKeywordDefinition.class);
        if (keywordDefinition != null) {
            return findLocalVariableUsages(keywordDefinition, normalName);
        }

        RobotVariablesTable variablesTable = PsiTreeUtil.getParentOfType(robotVariable, RobotVariablesTable.class);
        if (variablesTable != null) {
            return findUsagesOfVariableFromVariablesTable(robotVariable, normalName);
        }

        return Lists.newArrayList();
    }

    private static List<PsiElement> findUsagesOfVariableFromVariablesTable(PsiElement sourceVariable, String normalName) {
        final Project project = sourceVariable.getProject();
        List<PsiElement> usages = Lists.newArrayList();

        Collection<RobotScalarVariable> scalarVariablesWithSameName = StubIndex.getElements(RobotVariableNormalizedNameIndex.KEY, normalName, project,
                GlobalSearchScope.allScope(project), RobotScalarVariable.class);
        for (RobotScalarVariable robotScalarVariable: scalarVariablesWithSameName) {
            if (isVariableUsage(sourceVariable, robotScalarVariable, normalName)) {
                usages.add(robotScalarVariable);
            }
        }

        return usages;
    }

    private static boolean isVariableUsage(PsiElement sourceVariable, PsiElement usageVariable, final String sourceNormalName) {
        Optional<String> optVariableName = getVariableName(usageVariable);
        if (!optVariableName.isPresent()) {
            return false;
        }
        String normalName = RobotPsiUtil.normalizeKeywordForIndex(optVariableName.get());
        if (!sourceNormalName.equals(normalName)) {
            return false;
        }
        RobotVariableReference ref = new RobotVariableReference(usageVariable);
        PsiElement resolvesTo = ref.resolve();
        if (resolvesTo == null) {
            return false;
        }
        return RobotPsiUtil.areIdenticalTextualOccurrences(sourceVariable, resolvesTo);
    }

    private static List<PsiElement> findLocalVariableUsages(RobotTestCase test, final String expectedNormalName) {
        Collection<RobotScalarVariable> vars = PsiTreeUtil.findChildrenOfType(test, RobotScalarVariable.class);
        Collection<RobotScalarAssignment> assignments = PsiTreeUtil.findChildrenOfType(test, RobotScalarAssignment.class);
        List<PsiElement> allTypes = Lists.newArrayList();
        allTypes.addAll(vars);
        allTypes.addAll(assignments);

        List<PsiElement> usages = Lists.newArrayList();

        for (PsiElement el: allTypes) {
            Optional<String> optVariableName = getVariableName(el);
            if (optVariableName.isPresent()) {
                String name = optVariableName.get();
                String normalName = RobotPsiUtil.normalizeKeywordForIndex(name);
                if (normalName.equals(expectedNormalName)) {
                    usages.add(el);
                }
            }
        }
        return usages;
    }

    private static List<PsiElement> findLocalVariableUsages(RobotKeywordDefinition keywordDefinition, final String expectedNormalName) {
        Collection<RobotScalarVariable> vars = PsiTreeUtil.findChildrenOfType(keywordDefinition, RobotScalarVariable.class);
        Collection<RobotScalarAssignment> assignments = PsiTreeUtil.findChildrenOfType(keywordDefinition, RobotScalarAssignment.class);
        List<PsiElement> allTypes = Lists.newArrayList();
        allTypes.addAll(vars);
        allTypes.addAll(assignments);

        List<PsiElement> usages = Lists.newArrayList();

        for (PsiElement el: allTypes) {
            RobotArgumentDef parent = PsiTreeUtil.getParentOfType(el, RobotArgumentDef.class);
            if (parent != null) {
                continue; //Do not include keyword arguments in usages.
            }
            Optional<String> optVariableName = getVariableName(el);
            if (optVariableName.isPresent()) {
                String name = optVariableName.get();
                String normalName = RobotPsiUtil.normalizeKeywordForIndex(name);
                if (normalName.equals(expectedNormalName)) {
                    usages.add(el);
                }
            }
        }
        return usages;
    }
}