package ru.curs.celesta.intellij.linemarkers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.sql.psi.SqlElementTypes
import com.intellij.sql.psi.SqlFile
import com.intellij.sql.psi.impl.SqlTokenElement
import com.intellij.util.Function
import com.intellij.util.PsiNavigateUtil
import ru.curs.celesta.intellij.CELESTA_NOTIFICATIONS
import ru.curs.celesta.intellij.CelestaBundle
import ru.curs.celesta.intellij.generated.GeneratedClassesSearch
import ru.curs.celesta.intellij.prev
import ru.curs.celesta.intellij.scores.CelestaGrain
import java.awt.event.MouseEvent

abstract class CelestaSqlDefinitionLineMarkerProvider : LineMarkerProvider {

    protected abstract val type: SqlDefinitionNavigator.TargetType

    abstract fun check(element: PsiElement): Boolean

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val matches = element.containingFile is SqlFile && check(element)

        if (!matches)
            return null

        return LineMarkerInfo(
            element as SqlTokenElement,
            element.textRange,
            AllIcons.Nodes.Class,
            tooltipProvider,
            SqlDefinitionNavigator(element.project, type),
            GutterIconRenderer.Alignment.CENTER
        )
    }
}

private val tooltipProvider: Function<in SqlTokenElement, String> = Function {
    CelestaBundle.message("lineMarker.sqlDefinition.hint")
}

class SqlDefinitionNavigator(project: Project, val type: TargetType) : BaseNavigator<SqlTokenElement>(project) {
    override fun navigate(e: MouseEvent?, elt: SqlTokenElement) {
        val element = findElementNavigateTo(elt)
        if (element != null)
            PsiNavigateUtil.navigate(element)
        else
            CELESTA_NOTIFICATIONS.createNotification(
                CelestaBundle.message("lineMarker.generatedSources.unableToFindDeclaration"),
                NotificationType.WARNING
            ).notify(project)
    }

    private fun findElementNavigateTo(elt: SqlTokenElement): PsiElement? = notificationOnFail {
        val grain = CelestaGrain(elt.containingFile as SqlFile)

        val objectName = elt.text

        val grainName = grain.grainName ?: fail("Unable to determine grain name")

        val module = ModuleUtilCore.findModuleForFile(elt.containingFile) ?: fail("Unable to determine module")

        val generatedClassesSearch = GeneratedClassesSearch.getInstance(project)

        val searchScope = module.getModuleWithDependenciesAndLibrariesScope(true)

        val cursor = when (type) {
            TargetType.Table -> generatedClassesSearch.searchTableCursor(grainName, objectName, searchScope)
            TargetType.MaterializedView -> generatedClassesSearch.searchMaterializedViewCursor(
                grainName,
                objectName,
                searchScope
            )
        }

        cursor?.cursorClass
    }

    enum class TargetType {
        Table, MaterializedView
    }
}

class CelestaMaterializedViewDefinitionLineMarkerProvider : CelestaSqlDefinitionLineMarkerProvider() {
    override val type: SqlDefinitionNavigator.TargetType = SqlDefinitionNavigator.TargetType.MaterializedView

    override fun check(element: PsiElement): Boolean {
        return element.elementType == SqlElementTypes.SQL_IDENT
                && element.prev()?.elementType == SqlElementTypes.SQL_VIEW
                && element.prev().prev()?.let {
            it.elementType == SqlElementTypes.SQL_IDENT && it.text.toLowerCase() == "materialized"
        } ?: false
                && element.prev().prev().prev()?.elementType == SqlElementTypes.SQL_CREATE
    }
}


class CelestaTableDefinitionLineMarkerProvider : CelestaSqlDefinitionLineMarkerProvider() {
    override val type: SqlDefinitionNavigator.TargetType = SqlDefinitionNavigator.TargetType.Table

    override fun check(element: PsiElement): Boolean =
        element.parent?.elementType == SqlElementTypes.SQL_IDENTIFIER
                && element.parent?.parent?.elementType == SqlElementTypes.SQL_TABLE_REFERENCE
                && element.parent?.parent?.parent?.elementType == SqlElementTypes.SQL_CREATE_TABLE_STATEMENT
}