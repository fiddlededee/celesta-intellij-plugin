package ru.curs.celesta.intellij.linemarkers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.util.parentOfType
import com.intellij.util.CommonProcessors
import com.intellij.util.PsiNavigateUtil
import ru.curs.celesta.intellij.CELESTA_NOTIFICATIONS
import ru.curs.celesta.intellij.CelestaBundle
import ru.curs.celesta.intellij.CelestaConstants
import ru.curs.celesta.intellij.scores.CelestaGrain
import ru.curs.celesta.intellij.scores.CelestaScoreSearch
import java.awt.event.MouseEvent

abstract class CelestaGeneratedClassLineMarkerProvider : LineMarkerProvider {
    protected abstract val parentFqn: String
    protected abstract val objectExtractor: ObjectExtractor

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null

        val psiClass = element.parent as? PsiClass ?: return null

        val psiFacade = JavaPsiFacade.getInstance(element.project)

        val cursorClass = psiFacade.findClass(parentFqn, element.resolveScope) ?: return null

        if (psiClass.isInheritor(cursorClass, true)) {
            return LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.Ide.External_link_arrow,
                tooltipProvider,
                NavHandler(element.project, objectExtractor),
                GutterIconRenderer.Alignment.CENTER
            )
        }

        return null
    }
}

private val tooltipProvider: com.intellij.util.Function<in PsiIdentifier, String> = com.intellij.util.Function {
    val cursorClass = it.parent as PsiClass
    CelestaBundle.message("lineMarker.generatedSources.hint", cursorClass.qualifiedName ?: "")
}
private class NavHandler(
    project: Project,
    private val objectExtractor: ObjectExtractor
) : BaseNavigator<PsiIdentifier>(project) {

    val constantEvaluationHelper = JavaPsiFacade.getInstance(project).constantEvaluationHelper
    val scoreSearch = CelestaScoreSearch.getInstance(project)

    override fun navigate(e: MouseEvent, elt: PsiIdentifier) {
        val element = findElementNavigateTo(elt)
        if (element != null)
            PsiNavigateUtil.navigate(element)
        else
            CELESTA_NOTIFICATIONS.createNotification(
                CelestaBundle.message("lineMarker.generatedSources.unableToFindDeclaration"),
                NotificationType.WARNING
            ).notify(project)
    }

    private fun findElementNavigateTo(elt: PsiIdentifier): PsiElement? = notificationOnFail {
        val cursorClass = elt.parentOfType<PsiClass>()!!

        fun getConstant(
            grainNameField: String
        ): String? {
            return cursorClass.findFieldByName(grainNameField, false)
                ?.initializer
                ?.let {
                    constantEvaluationHelper.computeConstantExpression(it)
                } as? String
        }

        val module = ModuleUtilCore.findModuleForPsiElement(elt)
            ?: fail(CelestaBundle.message("lineMarker.generatedSources.unknownModule", elt.containingFile.virtualFile.path))

        val grainName = getConstant(CelestaConstants.GRAIN_NAME_FIELD)
            ?: fail(CelestaBundle.message("lineMarker.generatedSources.unknownGrain"))

        val tableName = getConstant(CelestaConstants.OBJECT_NAME_FIELD)
            ?: fail(CelestaBundle.message("lineMarker.generatedSources.unknownObject"))

        val collectProcessor = CommonProcessors.CollectProcessor<CelestaGrain>()
        scoreSearch.processScores(module, collectProcessor)

        collectProcessor
            .results
            .asSequence()
            .filter { it.grainName == grainName }
            .mapNotNull { it.objectExtractor(tableName) }
            .firstOrNull()
    }
}

typealias ObjectExtractor = CelestaGrain.(String) -> PsiElement?