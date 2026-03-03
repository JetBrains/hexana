// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitGate extends PsiElement {

  @NotNull
  List<WitDeprecatedGate> getDeprecatedGateList();

  @NotNull
  List<WitSinceGate> getSinceGateList();

  @NotNull
  List<WitUnstableGate> getUnstableGateList();

}
