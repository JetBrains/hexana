package org.jetbrains.hexana.wit.psi.impl;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.hexana.wit.psi.WitIncludeNamesItem;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class WitIncludeNamesItemManipulator extends AbstractElementManipulator<WitIncludeNamesItem> {
    @Override
    public @Nullable WitIncludeNamesItem handleContentChange(@NonNull WitIncludeNamesItem element, @NotNull TextRange range, String newContent) throws IncorrectOperationException {
        return null;// todo
    }
}
