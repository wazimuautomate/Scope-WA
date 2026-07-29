package com.tricreta.scopewa.ui.templates

import com.tricreta.scopewa.data.db.entity.TemplateEntity

/**
 * The Templates screen is two destinations, not one: the saved list, and the
 * editor for a single template. Only the list is on the main nav
 * (`ScopeWaDestination.Templates`); the editor is reached from it.
 */
object TemplateRoutes {

    const val ARG_TEMPLATE_ID = "templateId"

    const val EDITOR = "templates/edit/{$ARG_TEMPLATE_ID}"

    fun editor(templateId: Long = TemplateEntity.NEW_TEMPLATE_ID): String =
        "templates/edit/$templateId"
}
