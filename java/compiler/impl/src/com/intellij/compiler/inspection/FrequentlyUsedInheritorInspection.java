/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.compiler.inspection;

import com.intellij.codeInspection.*;
import com.intellij.compiler.CompilerReferenceService;
import com.intellij.compiler.backwardRefs.CompilerReferenceServiceEx;
import com.intellij.compiler.backwardRefs.ReferenceIndexUnavailableException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.java.stubs.index.JavaFullClassNameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.util.SystemProperties;
import one.util.streamex.MoreCollectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.backwardRefs.LightRef;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FrequentlyUsedInheritorInspection extends BaseJavaLocalInspectionTool {
  private static final Logger LOG = Logger.getInstance(FrequentlyUsedInheritorInspection.class);

  public static final byte MAX_RESULT = 3;
  private static final int PERCENT_THRESHOLD = SystemProperties.getIntProperty("FrequentlyUsedInheritorInspection.percent.threshold", 20);

  @Nullable
  @Override
  public ProblemDescriptor[] checkClass(@NotNull final PsiClass aClass,
                                        @NotNull final InspectionManager manager,
                                        final boolean isOnTheFly) {
    if (aClass instanceof PsiTypeParameter || aClass.isEnum()) {
      return null;
    }

    final Pair<PsiClass, PsiElement> superClassAndPlace = getSuperIfOnlyOne(aClass);
    if (superClassAndPlace == null) return null;

    PsiClass superClass = superClassAndPlace.getFirst();
    long ms = System.currentTimeMillis();
    final List<ClassAndInheritorCount> topInheritors = getTopInheritorsUsingCompilerIndices(superClass, aClass.getResolveScope(), aClass);
    if (LOG.isDebugEnabled()) {
      LOG.debug("search for inheritance structure of " + superClass.getQualifiedName() + " in " + (System.currentTimeMillis() - ms) + " ms");
    }
    if (topInheritors.isEmpty()) return null;

    final Collection<LocalQuickFix> topInheritorsQuickFix = new ArrayList<>(topInheritors.size());
    for (final ClassAndInheritorCount searchResult : topInheritors) {
      PsiClass psi = searchResult.psi;
      if (InheritanceUtil.isInheritorOrSelf(psi, aClass, true)) {
        continue;
      }
      final LocalQuickFix quickFix = new ChangeSuperClassFix(aClass,
                                                             psi,
                                                             superClass,
                                                             searchResult.number,
                                                             searchResult.psi.isInterface() && !aClass.isInterface());
      topInheritorsQuickFix.add(quickFix);
      if (topInheritorsQuickFix.size() >= MAX_RESULT) {
        break;
      }
    }

    PsiElement highlightingElement;
    if (aClass.getFields().length == 0 &&
        aClass.getMethods().length == 0 &&
        aClass.getInnerClasses().length == 0 &&
        aClass.getInitializers().length == 0) {
      highlightingElement = aClass;
    }
    else if (aClass instanceof PsiAnonymousClass) {
      highlightingElement = ((PsiAnonymousClass)aClass).getBaseClassReference();
    }
    else {
      highlightingElement = superClassAndPlace.getSecond();
    }

    return new ProblemDescriptor[]{manager
      .createProblemDescriptor(highlightingElement,
                               "Class can have more common super class",
                               isOnTheFly,
                               topInheritorsQuickFix.toArray(LocalQuickFix.EMPTY_ARRAY),
                               ProblemHighlightType.GENERIC_ERROR_OR_WARNING)};
  }

  @Nullable
  private static Pair<PsiClass, PsiElement> getSuperIfOnlyOne(@NotNull final PsiClass aClass) {
    PsiClass superClass = aClass.getSuperClass();
    if (superClass != null && !CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) {
      return isInSourceContent(aClass) ? Pair.create(superClass, aClass.getExtendsList()) : null;
    }

    PsiClass anInterface = Arrays
      .stream(aClass.getInterfaces())
      .filter(c -> !CommonClassNames.JAVA_LANG_OBJECT.equals(c.getQualifiedName()))
      .filter(c -> isInSourceContent(c))
      .collect(MoreCollectors.onlyOne())
      .orElse(null);
    if (anInterface == null) {
      return null;
    }
    else {
      return Pair.create(anInterface, aClass.isInterface() ? aClass.getExtendsList() : aClass.getImplementsList());
    }
  }

  @NotNull
  private static List<ClassAndInheritorCount> getTopInheritorsUsingCompilerIndices(@NotNull PsiClass aClass,
                                                                                   @NotNull GlobalSearchScope searchScope,
                                                                                   @NotNull PsiElement place) {
    String qName = aClass.getQualifiedName();
    if (qName == null) return Collections.emptyList();

    final Project project = aClass.getProject();
    final CompilerReferenceServiceEx compilerRefService = (CompilerReferenceServiceEx)CompilerReferenceService.getInstance(project);
    try {
      int id = compilerRefService.getNameId(qName);
      if (id == 0) return Collections.emptyList();
      return findInheritors(aClass, new LightRef.JavaLightClassRef(id), searchScope, place, -1, project, compilerRefService);
    }
    catch (ReferenceIndexUnavailableException e) {
      return Collections.emptyList();
    }
  }

  private static List<ClassAndInheritorCount> findInheritors(@NotNull PsiClass aClass,
                                                             @NotNull LightRef.JavaLightClassRef classAsLightRef,
                                                             @NotNull GlobalSearchScope searchScope,
                                                             @NotNull PsiElement place,
                                                             int hierarchyCardinality,
                                                             @NotNull Project project,
                                                             @NotNull CompilerReferenceServiceEx compilerRefService) {
    LightRef.LightClassHierarchyElementDef[] directInheritors = compilerRefService.getDirectInheritors(classAsLightRef);

    if (hierarchyCardinality == -1) {
      hierarchyCardinality = compilerRefService.getInheritorCount(classAsLightRef);
      if (hierarchyCardinality == -1) {
        return Collections.emptyList();
      }
    }
    int finalHierarchyCardinality = hierarchyCardinality;

    List<ClassAndInheritorCount> directInheritorStats = Stream
      .of(directInheritors)
      .filter(inheritor -> !(inheritor instanceof LightRef.LightAnonymousClassDef))
      .map(inheritor -> {
        ProgressManager.checkCanceled();
        int count = compilerRefService.getInheritorCount(inheritor);
        if (count != 1 && count * 100 > finalHierarchyCardinality * PERCENT_THRESHOLD) {
          return new Object() {
            final LightRef.LightClassHierarchyElementDef myDef = inheritor;
            final int inheritorCount = count;
          };
        }
        return null;
      })
      .filter(Objects::nonNull)
      .map(defAndCount -> {
        String name = compilerRefService.getName(defAndCount.myDef.getName());
        PsiClass inheritor =
          JavaFullClassNameIndex.getInstance().get(name.hashCode(), project, searchScope).stream()
            .filter(cls -> name.equals(cls.getQualifiedName()))
            .collect(MoreCollectors.onlyOne())
            .orElse(null);

        if (inheritor == null || !inheritor.isInheritor(aClass, false)) {
          return null;
        }

        return new ClassAndInheritorCount(inheritor, defAndCount.myDef, defAndCount.inheritorCount);
      })
      .filter(Objects::nonNull)
      .collect(Collectors.toList());

    PsiResolveHelper resolveHelper = PsiResolveHelper.SERVICE.getInstance(project);
    return directInheritorStats
      .stream()
      .filter(c -> resolveHelper.isAccessible(c.psi, place, null))
      .flatMap(c -> Stream.concat(Stream.of(c), getClassesIfInterface(c, finalHierarchyCardinality, searchScope, place, project, compilerRefService).stream()))
      .sorted()
      .limit(MAX_RESULT)
      .collect(Collectors.toList());
  }

  private static List<ClassAndInheritorCount> getClassesIfInterface(@NotNull ClassAndInheritorCount classAndInheritorCount,
                                                                    int hierarchyCardinality,
                                                                    GlobalSearchScope searchScope,
                                                                    PsiElement place,
                                                                    Project project,
                                                                    CompilerReferenceServiceEx compilerRefService) {
    if (classAndInheritorCount.psi.isInterface()) {
      return findInheritors(classAndInheritorCount.psi,
                            (LightRef.JavaLightClassRef)classAndInheritorCount.descriptor,
                            searchScope,
                            place,
                            hierarchyCardinality,
                            project,
                            compilerRefService);
    }
    return Collections.emptyList();
  }

  private static boolean isInSourceContent(@NotNull PsiElement e) {
    final VirtualFile file = e.getContainingFile().getVirtualFile();
    if (file == null) return false;
    final ProjectFileIndex index = ProjectRootManager.getInstance(e.getProject()).getFileIndex();
    return index.isInContent(file);
  }

  private static class ClassAndInheritorCount implements Comparable<ClassAndInheritorCount> {
    private final PsiClass psi;
    private final LightRef.LightClassHierarchyElementDef descriptor;
    private final int number;

    private ClassAndInheritorCount(PsiClass psi,
                                   LightRef.LightClassHierarchyElementDef descriptor,
                                   int number) {
      this.psi = psi;
      this.descriptor = descriptor;
      this.number = number;
    }

    @Override
    public int compareTo(@NotNull ClassAndInheritorCount o) {
      return - number + o.number;
    }
  }
}
