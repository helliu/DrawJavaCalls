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
        
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset)
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        
        val title: String
        val linkReference: String
        
        if (psiFile is PsiJavaFile && method != null) {
            title = method.name
            linkReference = "#${method.name}"
        } else {
            val document = editor.document
            val lineNumber = document.getLineNumber(offset) + 1
            title = psiFile.name
            linkReference = ":$lineNumber"
        }
        
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

        updateDiagram(javaMethodDiagram, filePath, title, group, linkReference)
        
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DrawJavaCalls")
        toolWindow?.show {
            val content = toolWindow.contentManager.getContent(0)
        }
        
        refreshToolWindow(project)
    }
    
    abstract fun updateDiagram(service: JavaMethodDiagram, filePath: String, title: String, group: String? = null, linkReference: String)

    private fun refreshToolWindow(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DrawJavaCalls")
        if (toolWindow != null) {
            val content = toolWindow.contentManager.getContent(0)
        }
    }
}

class AddDiagramNodeAction : AddDiagramNodeBaseAction() {
    override fun updateDiagram(service: JavaMethodDiagram, filePath: String, title: String, group: String?, linkReference: String) {
        service.addNode(filePath, title, group, linkReference)
    }
}

class AddDiagramSiblingNodeAction : AddDiagramNodeBaseAction() {
    override fun updateDiagram(service: JavaMethodDiagram, filePath: String, title: String, group: String?, linkReference: String) {
        service.addSibling(filePath, title, group, linkReference)
    }
}
