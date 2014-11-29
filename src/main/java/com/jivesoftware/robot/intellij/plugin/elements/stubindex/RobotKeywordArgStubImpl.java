package com.jivesoftware.robot.intellij.plugin.elements.stubindex;

import com.intellij.psi.stubs.IStubElementType;
import com.intellij.psi.stubs.StubBase;
import com.intellij.psi.stubs.StubElement;
import com.intellij.util.io.StringRef;
import com.jivesoftware.robot.intellij.plugin.psi.RobotKeywordArg;
import org.jetbrains.annotations.Nullable;

/**
 * Created by charles on 6/9/14.
 */
public class RobotKeywordArgStubImpl extends StubBase<RobotKeywordArg> implements RobotKeywordArgStub {
    private StringRef name;

    public RobotKeywordArgStubImpl(final StubElement parent, final IStubElementType elementType, StringRef name) {
        super(parent, elementType);
        this.name = name;
    }

    @Nullable
    @Override
    public String getName() {
        return name.getString();
    }
}
