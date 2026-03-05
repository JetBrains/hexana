package org.jetbrains.hexana.wit.psi.impl;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.hexana.wit.psi.WitIncludeNamesItem;
import org.jetbrains.hexana.wit.psi.WitUseNamesItem;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class WitUseNamesItemManipulator extends AbstractElementManipulator<WitUseNamesItem> {
    @Override
    public @Nullable WitUseNamesItem handleContentChange(@NonNull WitUseNamesItem element, @NotNull TextRange range, String newContent) throws IncorrectOperationException {
        return null;// todo
    }
}

