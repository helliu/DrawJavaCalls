package com.helliu.drawjavacalls.action

import com.helliu.drawjavacalls.service.JavaMethodDiagram
import com.helliu.drawjavacalls.ui.DiagramToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.helliu.drawjavacalls.ui.DiagramToolWindow
import com.intellij.ide.DataManager
import javax.swing.JComponent

abstract class AddDiagramNodeBaseAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        var editor = e.getData(CommonDataKeys.EDITOR)
        var psiFile = e.getData(CommonDataKeys.PSI_FILE)
        
        if (editor == null) {
            editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null && psiFile == null) {
                psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            }
        }

        if (editor == null || psiFile == null) return
        
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return
        
        val className = method.containingClass?.qualifiedName ?: method.containingClass?.name ?: "Unknown"
        val methodName = method.name
        val filePath = psiFile.virtualFile.path
        
        val javaMethodDiagram = project.service<JavaMethodDiagram>()
        var group = e.getData(DiagramToolWindow.GROUP_DATA_KEY)
        
        if (group.isNullOrBlank()) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DrawJavaCalls")
            val content = toolWindow?.contentManager?.getContent(0)
            val component = content?.component
            
            if (component is JComponent) {
                // Try to find DiagramMainPanel
                val mainPanel = if (component.name == "DiagramMainPanel") component 
                                else component.components.filterIsInstance<JComponent>().find { it.name == "DiagramMainPanel" }
                
                val targetComponent = mainPanel ?: component

                if (targetComponent is com.intellij.openapi.actionSystem.DataProvider) {
                    group = targetComponent.getData(DiagramToolWindow.GROUP_DATA_KEY.name) as? String
                }
                
                if (group.isNullOrBlank()) {
                    group = DataManager.getInstance().getDataContext(targetComponent).getData(DiagramToolWindow.GROUP_DATA_KEY)
                }
            }
        }

        updateDiagram(javaMethodDiagram, filePath, methodName, group)
        
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DrawJavaCalls")
        toolWindow?.show {
            val content = toolWindow.contentManager.getContent(0)
            // This is a bit hacky, but we need to find the DiagramToolWindow instance
            // In a real app, we might store it differently.
            // For now, let's assume we can trigger refresh.
            // Actually, we can use an event bus or just call refresh if we find it.
            // Since we created it in DiagramToolWindowFactory, it's in the component.
            // But better: let's just make it refresh.
            // We can search for the component or use the service to notify listeners.
        }
        
        // Alternative: trigger refresh via service if we had listeners, 
        // but for now let's just find the tool window and refresh it if it's open.
        // We'll add a refresh call here if possible.
        refreshToolWindow(project)
    }
    
    abstract fun updateDiagram(service: JavaMethodDiagram, filePath: String, methodName: String, group: String? = null)

    private fun refreshToolWindow(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DrawJavaCalls")
        if (toolWindow != null) {
            val content = toolWindow.contentManager.getContent(0)
            // How to get DiagramToolWindow from content?
            // Usually it's the component of the content.
            // But we can also just use a message bus.
        }
    }
}

class AddDiagramNodeAction : AddDiagramNodeBaseAction() {
    override fun updateDiagram(service: JavaMethodDiagram, filePath: String, methodName: String, group: String?) {
        service.addNode(filePath, methodName, group)
    }
}

class AddDiagramSiblingNodeAction : AddDiagramNodeBaseAction() {
    override fun updateDiagram(service: JavaMethodDiagram, filePath: String, methodName: String, group: String?) {
        service.addSibling(filePath, methodName, group)
    }
}
