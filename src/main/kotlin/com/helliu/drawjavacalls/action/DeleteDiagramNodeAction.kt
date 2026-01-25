package com.helliu.drawjavacalls.action

import com.helliu.drawjavacalls.service.JavaMethodDiagram
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class DeleteDiagramNodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val javaMethodDiagram = project.service<JavaMethodDiagram>()
        javaMethodDiagram.deleteSelectedNode()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val javaMethodDiagram = project.service<JavaMethodDiagram>()
        e.presentation.isEnabled = javaMethodDiagram.selectedDiagramNode != null
    }
}
