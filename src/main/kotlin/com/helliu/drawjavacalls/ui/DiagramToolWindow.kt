package com.helliu.drawjavacalls.ui

import com.helliu.drawjavacalls.model.DiagramType
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.*
import javax.swing.*
import com.helliu.drawjavacalls.service.DiagramSettings
import com.helliu.drawjavacalls.service.JavaMethodDiagram
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import net.sourceforge.plantuml.SourceStringReader
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.FileFormat
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ide.DataManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.net.URLDecoder
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil

class DiagramToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val diagramToolWindow = DiagramToolWindow(project)
        val content = ContentFactory.getInstance().createContent(diagramToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class DiagramToolWindow(private val project: Project) {
    /**
     * FlowLayout subclass that fully supports wrapping of components.
     */
    private class WrapLayout : FlowLayout {
        constructor() : super()
        constructor(align: Int) : super(align)
        constructor(align: Int, hgap: Int, vgap: Int) : super(align, hgap, vgap)

        override fun preferredLayoutSize(target: Container): Dimension {
            return layoutSize(target, true)
        }

        override fun minimumLayoutSize(target: Container): Dimension {
            val minimum = layoutSize(target, false)
            minimum.width -= hgap + 1
            return minimum
        }

        private fun layoutSize(target: Container, preferred: Boolean): Dimension {
            synchronized(target.treeLock) {
                var targetWidth = target.size.width
                if (targetWidth == 0) targetWidth = Int.MAX_VALUE

                val insets = target.insets
                val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
                val maxWidth = targetWidth - horizontalInsetsAndGap

                val dim = Dimension(0, 0)
                var rowWidth = 0
                var rowHeight = 0

                val nmembers = target.componentCount

                for (i in 0 until nmembers) {
                    val m = target.getComponent(i)

                    if (m.isVisible) {
                        val d = if (preferred) m.preferredSize else m.minimumSize

                        if (rowWidth + d.width > maxWidth) {
                            addRow(dim, rowWidth, rowHeight)
                            rowWidth = 0
                            rowHeight = 0
                        }

                        if (rowWidth != 0) {
                            rowWidth += hgap
                        }

                        rowWidth += d.width
                        rowHeight = Math.max(rowHeight, d.height)
                    }
                }

                addRow(dim, rowWidth, rowHeight)

                dim.width += horizontalInsetsAndGap
                dim.height += insets.top + insets.bottom + vgap * 2

                val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
                if (scrollPane != null && target.isValid) {
                    dim.width -= hgap + 1
                }

                return dim
            }
        }

        private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
            dim.width = Math.max(dim.width, rowWidth)

            if (dim.height > 0) {
                dim.height += vgap
            }

            dim.height += rowHeight
        }
    }
    private val panel = JPanel(BorderLayout())
    private val browser = JBCefBrowser()
    private val javaMethodDiagram = project.service<JavaMethodDiagram>()
    private val jsQuery = JBCefJSQuery.create(browser)
    private val groupNewNodeField = JTextField(10)
    private val diagramTypeCombo = JComboBox(DiagramType.values())
    private val useProjectRootCheckBox = JCheckBox("Use \$projectsPath", true)
    private val loadFromEditorCheckBox = JCheckBox("Load from editor", true)
    private val customRootField = JTextField()
    private val browseCustomRootButton = JButton(AllIcons.Actions.MenuOpen)

    fun getGroupNewNode(): String = groupNewNodeField.text

    companion object {
        val GROUP_DATA_KEY = DataKey.create<String>("DiagramGroup")
    }

    init {
        jsQuery.addHandler { identifier ->
            javaMethodDiagram.selectNode(identifier)
            null
        }
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                user_gesture: Boolean,
                is_redirect: Boolean
            ): Boolean {
                val url = request?.url ?: return false
                if (url.startsWith("file://") || (url.contains(":") && !url.startsWith("http"))) {
                    if (url.endsWith(".js") || url.endsWith(".css") || url.startsWith("https://") || url.startsWith("http://")) {
                        return false
                    }
                    // Try to open in IntelliJ
                    val decodedUrl = try {
                        URLDecoder.decode(url, "UTF-8")
                    } catch (e: Exception) {
                        url
                    }
                    
                    // Clean up the URL: file:///C:/... -> C:/...
                    var path = decodedUrl.removePrefix("file://")
                    if (path.startsWith("/") && path.getOrNull(2) == ':') {
                        path = path.substring(1)
                    }

                    // Normalize slashes for easier processing
                    path = path.replace("\\", "/")

                    val parts = if (path.contains("#")) {
                        path.split("#")
                    } else {
                        // Handle line numbers like C:/path/to/file:52
                        val lastColonIndex = path.lastIndexOf(':')
                        if (lastColonIndex > 2) { // Ensure it's not the drive letter colon (C:)
                            listOf(path.substring(0, lastColonIndex), ":" + path.substring(lastColonIndex + 1))
                        } else {
                            listOf(path)
                        }
                    }
                    
                    val filePath = parts[0]
                    val anchor = if (parts.size > 1) parts[1] else null
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                    if (virtualFile != null) {
                        ApplicationManager.getApplication().invokeLater {
                            var descriptor = OpenFileDescriptor(project, virtualFile)
                            
                            if (anchor != null) {
                                if (anchor.startsWith(":")) {
                                    val lineNumber = anchor.substring(1).toIntOrNull()
                                    if (lineNumber != null) {
                                        descriptor = OpenFileDescriptor(project, virtualFile, lineNumber - 1, 0)
                                    }
                                } else {
                                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                                    if (psiFile != null) {
                                        val cleanAnchor = anchor.removeSuffix("()").trim()
                                        val elements = PsiTreeUtil.findChildrenOfAnyType(psiFile, PsiNameIdentifierOwner::class.java)
                                        val target = elements.find { it.name == cleanAnchor }
                                        if (target != null) {
                                            descriptor = OpenFileDescriptor(project, virtualFile, target.textOffset)
                                        }
                                    }
                                }
                            }
                            descriptor.navigate(true)
                        }
                        return true // Intercepted
                    }
                }
                return false
            }
        }, browser.cefBrowser)

        javaMethodDiagram.addChangeListener(object : JavaMethodDiagram.DiagramChangeListener {
            override fun onDiagramChanged() {
                refreshDiagram()
            }
        })

        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                val file = event.newFile
                if (loadFromEditorCheckBox.isSelected && file != null && (file.extension == "puml" || file.extension == "mmd" || file.extension == "drawio")) {
                    loadFile(file)
                }
            }
        })
        
        val mainPanel = object : JPanel(BorderLayout()), com.intellij.openapi.actionSystem.DataProvider {
            init {
                name = "DiagramMainPanel"
            }
            override fun getData(dataId: String): Any? {
                if (GROUP_DATA_KEY.`is`(dataId)) return groupNewNodeField.text
                return null
            }
        }

        // Initial check for selected file - Delay slightly to avoid conflict with listener and ensure project is fully ready
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let {
                if (loadFromEditorCheckBox.isSelected && (it.extension == "puml" || it.extension == "mmd" || it.extension == "drawio")) {
                    loadFile(it)
                }
            }
        }
        
        val deleteAction = ActionManager.getInstance().getAction("com.helliu.drawjavacalls.DeleteDiagramNodeAction")
        deleteAction?.registerCustomShortcutSet(deleteAction.shortcutSet, mainPanel)
        
        val addLevelAction = ActionManager.getInstance().getAction("com.helliu.drawjavacalls.AddDiagramNodeAction")
        addLevelAction?.registerCustomShortcutSet(addLevelAction.shortcutSet, mainPanel)

        val addSiblingAction = ActionManager.getInstance().getAction("com.helliu.drawjavacalls.AddDiagramSiblingNodeAction")
        addSiblingAction?.registerCustomShortcutSet(addSiblingAction.shortcutSet, mainPanel)


        fun triggerAction(actionId: String) {
            val action = ActionManager.getInstance().getAction(actionId) ?: return
            val event = AnActionEvent.createFromAnAction(action, null, "DiagramToolWindow", DataManager.getInstance().getDataContext(mainPanel))
            action.actionPerformed(event)
        }

        fun createButton(icon: javax.swing.Icon, tooltipText: String, actionId: String? = null): JButton {
            val button = JButton(icon)
            button.isBorderPainted = false
            button.isContentAreaFilled = false
            button.isFocusPainted = true
            button.margin = Insets(2, 2, 2, 2)
            var tooltip = tooltipText
            if (actionId != null) {
                val action = ActionManager.getInstance().getAction(actionId)
                if (action != null) {
                    val shortcutText = KeymapUtil.getFirstKeyboardShortcutText(action)
                    if (shortcutText.isNotEmpty()) {
                        tooltip += " ($shortcutText)"
                    }
                }
            }
            button.toolTipText = tooltip
            return button
        }

        // --- Group 1: Edit Diagram ---
        val addLevelButton = createButton(AllIcons.Actions.TraceInto, "Add Method (New Level)", "com.helliu.drawjavacalls.AddDiagramNodeAction")
        addLevelButton.addActionListener { triggerAction("com.helliu.drawjavacalls.AddDiagramNodeAction") }

        val addSiblingButton = createButton(AllIcons.Actions.TraceOver, "Add Sibling Method", "com.helliu.drawjavacalls.AddDiagramSiblingNodeAction")
        addSiblingButton.addActionListener { triggerAction("com.helliu.drawjavacalls.AddDiagramSiblingNodeAction") }

        val deleteNodeButton = createButton(AllIcons.Actions.DeleteTag, "Delete Node", "com.helliu.drawjavacalls.DeleteDiagramNodeAction")
        deleteNodeButton.addActionListener { triggerAction("com.helliu.drawjavacalls.DeleteDiagramNodeAction") }

        // --- Group 2: Element Details ---
        val editElementButton = createButton(AllIcons.Actions.Edit, "Edit Selected Element")
        editElementButton.addActionListener {
            val selected = javaMethodDiagram.selectedDiagramNode
            if (selected != null) {
                EditElementDialog(project, selected).show()
            } else {
                Messages.showInfoMessage(project, "Please select an element in the diagram first.", "No Element Selected")
            }
        }

        val editRelationsButton = createButton(AllIcons.Actions.ShowAsTree, "Edit Relations")
        editRelationsButton.addActionListener {
            EditRelationsDialog(project, javaMethodDiagram).show()
        }

        val openInEditorButton = createButton(AllIcons.Duplicates.SendToTheLeft, "Show in the Editor")
        openInEditorButton.addActionListener { openInEditor() }

        // --- Group 3: File Operations ---
        val newButton = createButton(AllIcons.Actions.ClearCash, "Clear/New Diagram")
        newButton.addActionListener {
            javaMethodDiagram.newDiagram()
            javaMethodDiagram.currentFilePath = null // Ensure it's cleared so reload works if same file is re-selected
            refreshDiagram()
        }

        val saveButton = createButton(AllIcons.Actions.MenuSaveall, "Save Diagram")
        saveButton.addActionListener { saveDiagram() }

        val loadButton = createButton(AllIcons.Actions.MenuOpen, "Load Diagram")
        loadButton.addActionListener { 
            javaMethodDiagram.currentFilePath = null // Force reload even if it was already "current"
            loadDiagram() 
        }

        // --- Build Toolbar ---
        val toolbar = JPanel(WrapLayout(FlowLayout.LEFT, 2, 2))
        
        toolbar.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                toolbar.revalidate()
            }
        })
        
        fun addSeparator() {
            val sep = JSeparator(JSeparator.VERTICAL)
            sep.preferredSize = Dimension(2, 20)
            toolbar.add(sep)
        }

        toolbar.add(addLevelButton)
        toolbar.add(addSiblingButton)
        toolbar.add(deleteNodeButton)
        addSeparator()
        
        toolbar.add(editElementButton)
        toolbar.add(editRelationsButton)
        toolbar.add(openInEditorButton)
        addSeparator()

        toolbar.add(newButton)
        toolbar.add(saveButton)
        toolbar.add(loadButton)
        addSeparator()

        val groupPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        groupPanel.isOpaque = false
        val groupLabel = JLabel(" Group: ")
        groupLabel.font = groupLabel.font.deriveFont(Font.BOLD, 11f)
        groupPanel.add(groupLabel)
        groupNewNodeField.maximumSize = Dimension(120, 24)
        groupNewNodeField.preferredSize = Dimension(120, 24)
        groupPanel.add(groupNewNodeField)
        toolbar.add(groupPanel)

        val bottomPanel = JPanel(BorderLayout())
        val settings = DiagramSettings.getInstance(project)
        val linkPanel = JPanel(WrapLayout(FlowLayout.LEFT, 5, 5))
        
        linkPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                linkPanel.revalidate()
            }
        })

        // Group 1: Diagram Type
        val typeGroupPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        typeGroupPanel.isOpaque = false
        typeGroupPanel.add(JLabel("Diagram Type: "))
        diagramTypeCombo.selectedItem = settings.diagramType
        diagramTypeCombo.addActionListener {
            val selected = diagramTypeCombo.selectedItem as? DiagramType
            if (selected != null) {
                settings.diagramType = selected
                refreshDiagram()
            }
        }
        typeGroupPanel.add(diagramTypeCombo)

        // Group 2: Project Root Checkbox
        useProjectRootCheckBox.isSelected = settings.useProjectRoot
        
        if (settings.useProjectRoot) {
            customRootField.text = project.basePath ?: ""
        } else {
            customRootField.text = settings.customRootPath
        }
        
        customRootField.columns = 20
        customRootField.isEnabled = true // Always enabled
        customRootField.minimumSize = Dimension(150, 24)
        browseCustomRootButton.isEnabled = true // Always enabled
        browseCustomRootButton.preferredSize = Dimension(24, 24)
        
        loadFromEditorCheckBox.isSelected = settings.loadFromEditor
        loadFromEditorCheckBox.toolTipText = "To automatically load the digram when a puml or mmd file is opened in the editor."
        loadFromEditorCheckBox.addActionListener {
            settings.loadFromEditor = loadFromEditorCheckBox.isSelected
        }

        useProjectRootCheckBox.addActionListener {
            settings.useProjectRoot = useProjectRootCheckBox.isSelected
            if (useProjectRootCheckBox.isSelected) {
                customRootField.text = project.basePath ?: ""
            } else {
                customRootField.text = settings.customRootPath
            }
            refreshDiagram()
        }

        customRootField.addActionListener {
            if (useProjectRootCheckBox.isSelected) {
                // If Use $projectsPath is checked, the input field value is used as the project root
            } else {
                settings.customRootPath = customRootField.text
            }
            refreshDiagram()
        }

        browseCustomRootButton.addActionListener {
            val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null)
            if (file != null) {
                customRootField.text = file.path
                if (!useProjectRootCheckBox.isSelected) {
                    settings.customRootPath = file.path
                }
                refreshDiagram()
            }
        }

        // Group 3: Project Value
        val projectValueGroupPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        projectValueGroupPanel.isOpaque = false
        projectValueGroupPanel.add(JLabel(" \$projectsPath value: "))
        projectValueGroupPanel.add(customRootField)
        projectValueGroupPanel.add(browseCustomRootButton)

        linkPanel.add(typeGroupPanel)
        linkPanel.add(useProjectRootCheckBox)
        linkPanel.add(loadFromEditorCheckBox)
        linkPanel.add(projectValueGroupPanel)
        
        bottomPanel.add(linkPanel, BorderLayout.CENTER)

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(browser.component, BorderLayout.CENTER)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)
        panel.add(mainPanel, BorderLayout.CENTER)
        
        // Ensure layout is valid on startup
        SwingUtilities.invokeLater {
            mainPanel.revalidate()
            mainPanel.repaint()
        }
    }

    fun getContent(): JPanel = panel

    fun refreshDiagram() {
        val customRoot = if (useProjectRootCheckBox.isSelected) customRootField.text else null
        var diagramContent = javaMethodDiagram.generateDiagram(customRoot)
        if (diagramContent.isEmpty()) {
            loadDiagram("")
        } else {
            if (customRoot != null && useProjectRootCheckBox.isSelected) {
                val projectFolderName = customRoot.replace("\\", "/").substringAfterLast('/')
                diagramContent = diagramContent.replace("\$projectsPath/" + projectFolderName, customRoot.replace("\\", "/"))
            }
            when (javaMethodDiagram.diagramType) {
                DiagramType.PLANT_UML -> {
                    val svg = convertPumlToSvg(diagramContent)
                    loadDiagram(svg)
                }
                DiagramType.MERMAID -> {
                    loadMermaidDiagram(diagramContent)
                }
                DiagramType.DRAW_IO -> {
                    loadDrawIoDiagram(diagramContent)
                }
            }
        }
    }

    private fun loadDrawIoDiagram(xml: String) {
        val html = """
            <html>
            <head>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        margin: 0;
                        padding: 20px;
                        background-color: #ffffff;
                    }
                    .mxgraph {
                        max-width: 100%;
                        border: 1px solid transparent;
                    }
                </style>
            </head>
            <body>
                <script id="xml-data" type="text/plain">${xml.replace("</script>", "<\\/script>")}</script>
                <div class="mxgraph" id="diagram-container"></div>
                <script>
                    const xml = document.getElementById('xml-data').textContent;
                    const container = document.getElementById('diagram-container');
                    container.setAttribute('data-mxgraph', JSON.stringify({
                        highlight: '#0078d4',
                        nav: true,
                        resize: true,
                        toolbar: '',
                        edit: '_blank',
                        xml: xml
                    }));
                </script>
                <script type="text/javascript" src="https://viewer.diagrams.net/js/viewer-static.min.js"></script>
            </body>
            </html>
        """.trimIndent()
        ApplicationManager.getApplication().invokeLater {
            browser.loadHTML(html)
        }
    }

    private fun loadMermaidDiagram(mermaidCode: String) {
        val selectedIdentifier = javaMethodDiagram.selectedDiagramNode?.getIdentifier() ?: ""
        val allIdentifiers = javaMethodDiagram.getAllNodes().map { it.getIdentifier() }
        val allIdentifiersJs = allIdentifiers.joinToString(",") { "'$it'" }

        val html = """
            <html>
            <head>
                <script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
                <style>
                    .nodes .node {
                        cursor: pointer;
                    }
                    .selected rect, .selected circle, .selected polygon, .selected path, .selected ellipse {
                        stroke: #0078d4 !important;
                        stroke-width: 3px !important;
                        fill: #e1f0fe !important;
                    }
                </style>
            </head>
            <body>
                <pre class="mermaid">
                    $mermaidCode
                </pre>
                <script>
                    mermaid.initialize({ 
                        startOnLoad: false,
                        securityLevel: 'loose'
                    });
                    
                    async function renderDiagram() {
                        const { render } = mermaid;
                        const container = document.querySelector('.mermaid');
                        const content = container.textContent;
                        const { svg } = await render('mermaid-svg', content);
                        container.innerHTML = svg;
                        setupInteractions();
                    }

                    document.addEventListener('DOMContentLoaded', function() {
                        renderDiagram();
                    });

                    function setupInteractions() {
                        const allIdentifiers = [$allIdentifiersJs];
                        const selectedIdentifier = '$selectedIdentifier';
                        
                        allIdentifiers.forEach(id => {
                            const nodeElement = document.querySelector('[id^="flowchart-' + id + '-"]');
                            if (nodeElement) {
                                nodeElement.classList.add('selectable');
                                if (id === selectedIdentifier) {
                                    nodeElement.classList.add('selected');
                                }

                                nodeElement.addEventListener('click', function() {
                                    const selected = document.querySelectorAll('.selected');
                                    selected.forEach(s => s.classList.remove('selected'));
                                    nodeElement.classList.add('selected');
                                    ${jsQuery.inject("id")}
                                });
                            }
                        });
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        //println(html)
        ApplicationManager.getApplication().invokeLater {
            browser.loadHTML(html)
        }
    }

    fun loadDiagram(svgPlantUmlDiagram: String) {
        if (svgPlantUmlDiagram.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                browser.loadHTML("<html><body></body></html>")
            }
            return
        }
        val selectedIdentifier = javaMethodDiagram.selectedDiagramNode?.getIdentifier() ?: ""
        val allIdentifiers = javaMethodDiagram.getAllNodes().map { it.getIdentifier() }
        val allIdentifiersJs = allIdentifiers.joinToString(",") { "'$it'" }

        val html = """
            <html>
            <head>
                <style>
                    rect.selectable {
                        cursor: pointer;
                    }
                    .selected {
                        stroke: #0078d4 !important;
                        stroke-width: 3px !important;
                        fill: #e1f0fe !important;
                    }
                </style>
            </head>
            <body>
                $svgPlantUmlDiagram
                <script>
                    document.addEventListener('DOMContentLoaded', function() {
                        const svgs = document.getElementsByTagName('svg');
                        if (svgs.length > 0) {
                            const svg = svgs[0];
                            const allIdentifiers = [$allIdentifiersJs];
                            const selectedIdentifier = '$selectedIdentifier';
                            
                            allIdentifiers.forEach(id => {
                                const gElement = document.getElementById(id);
                                if (gElement) {
                                    const rect = gElement.querySelector('rect');
                                    if (rect) {
                                        rect.classList.add('selectable');
                                        if (id === selectedIdentifier) {
                                            rect.classList.add('selected');
                                        }
                                        
                                        rect.addEventListener('click', function(e) {
                                            // Clear other selections
                                            const selected = document.querySelectorAll('.selected');
                                            selected.forEach(s => s.classList.remove('selected'));
                                            
                                            // Add selection to this one
                                            rect.classList.add('selected');
                                            
                                            // Notify Kotlin
                                            ${jsQuery.inject("id")}
                                        });
                                    }
                                }
                            });
                        }
                    });
                </script>
            </body>
            </html>
        """.trimIndent()

//        println("************* html")
//        println(html)
        ApplicationManager.getApplication().invokeLater {
            browser.loadHTML(html)
        }
    }

    fun isAsc(): Boolean {
        // As requested: provide a method 'boolean isAsc()', to know if that is a asc or desc diagram to be generated.
        // Defaulting to true for now, as no specific UI for this was requested yet but the method is required.
        return true
    }

    private fun convertPumlToSvg(puml: String): String {
        val reader = SourceStringReader(puml)
        val os = ByteArrayOutputStream()
        reader.outputImage(os, FileFormatOption(FileFormat.SVG))
        return os.toString(StandardCharsets.UTF_8.name())
    }

    private fun saveDiagram() {
        val generator = javaMethodDiagram.getGenerator()
        val extension = generator.getExtension()
        val descriptor = FileSaverDescriptor("Save Diagram", "Save ${javaMethodDiagram.diagramType.displayName} diagram", extension)
        val baseDir = getDiagramsDir()
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val fileWrapper = wrapper.save(baseDir, "diagram.$extension")
        if (fileWrapper != null) {
            val content = javaMethodDiagram.generateDiagram()
            WriteAction.run<Exception> {
                val parentDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(fileWrapper.file.parentFile)
                val virtualFile = parentDir?.createChildData(this, fileWrapper.file.name)
                if (virtualFile != null) {
                    VfsUtil.saveText(virtualFile, content)
                    javaMethodDiagram.currentFilePath = virtualFile.path
                }
            }
        }
    }


    private fun loadDiagram() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter { it.extension == "puml" || it.extension == "mmd" || it.extension == "drawio" }
        val baseDir = getDiagramsDir()
        val fileChooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val files = fileChooser.choose(project, baseDir)
        if (files.isNotEmpty()) {
            loadFile(files[0])
        }
    }

    private fun loadFile(file: VirtualFile) {
        if (javaMethodDiagram.currentFilePath == file.path) return
        javaMethodDiagram.currentFilePath = file.path

        if (file.extension == "mmd") {
            javaMethodDiagram.diagramType = DiagramType.MERMAID
        } else if (file.extension == "puml") {
            javaMethodDiagram.diagramType = DiagramType.PLANT_UML
        } else if (file.extension == "drawio") {
            javaMethodDiagram.diagramType = DiagramType.DRAW_IO
        } else {
            return
        }
        diagramTypeCombo.selectedItem = DiagramSettings.getInstance(project).diagramType

        val content = VfsUtil.loadText(file)
        
        if (content.trim().startsWith("'DrawJavaCalls Generated")) {
            javaMethodDiagram.loadDrawJavaCallDiagram(content, DiagramType.PLANT_UML)
            refreshDiagram()
            return
        } else if (content.trim().startsWith("%%DrawJavaCalls Generated")) {
            javaMethodDiagram.loadDrawJavaCallDiagram(content, DiagramType.MERMAID)
            refreshDiagram()
            return
        } else if (content.contains("<!-- DrawJavaCalls Generated -->")) {
            javaMethodDiagram.loadDrawJavaCallDiagram(content, DiagramType.DRAW_IO)
            refreshDiagram()
            return
        }

        var diagramToLoad = content
        if (useProjectRootCheckBox.isSelected) {
            val customRoot = customRootField.text
            if (customRoot.isNotEmpty()) {
                val projectFolderName = customRoot.replace("\\", "/").substringAfterLast('/')
                diagramToLoad = content.replace("\$projectsPath/" + projectFolderName, customRoot.replace("\\", "/"))
            }
        }
        if (javaMethodDiagram.diagramType == DiagramType.PLANT_UML) {
            val svg = convertPumlToSvg(diagramToLoad)
            loadDiagram(svg)
        } else if (javaMethodDiagram.diagramType == DiagramType.MERMAID) {
            loadMermaidDiagram(diagramToLoad)
        }
    }

    private fun openInEditor() {
        val path = javaMethodDiagram.currentFilePath
        if (path == null) {
            Messages.showInfoMessage(project, "Diagram not saved yet, save the diagram to open in the editor", "Open in Editor")
            return
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
        if (virtualFile != null) {
            OpenFileDescriptor(project, virtualFile).navigate(true)
        } else {
            Messages.showErrorDialog(project, "File not found: $path", "Open in Editor")
        }
    }

    private fun getDiagramsDir(): VirtualFile? {
        val homePath = System.getProperty("user.home")
        // Scratches and Consoles/scratches/Diagrams
        // Usually located in config directory. 
        // A better way to find it in IntelliJ:
        val scratchesDir = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(com.intellij.ide.scratch.ScratchFileService.getInstance().getRootPath(com.intellij.ide.scratch.RootType.findById("scratches"))))
        if (scratchesDir != null) {
            val diagramsDir = scratchesDir.findChild("Diagrams")
            if (diagramsDir == null) {
                return WriteAction.compute<VirtualFile, Exception> {
                    scratchesDir.createChildDirectory(this, "Diagrams")
                }
            }
            return diagramsDir
        }
        return null
    }
}

class EditElementDialog(private val project: Project, private val element: com.helliu.drawjavacalls.model.DiagramElement) : DialogWrapper(project) {
    private val filePathField = JTextField(element.filePath)
    private val titleField = JTextField(element.title)
    private val linkReferenceField = JTextField(element.linkReference)
    private val groupField = JTextField(element.group ?: "")

    init {
        title = "Edit Selected Element"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(2, 2, 2, 2)

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; panel.add(JLabel("File Path:"), gbc)
        
        val filePathPanel = JPanel(BorderLayout(5, 0))
        filePathPanel.add(filePathField, BorderLayout.CENTER)
        val browseButton = JButton(AllIcons.Actions.MenuOpen)
        browseButton.addActionListener {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            val initialFile = LocalFileSystem.getInstance().findFileByPath(filePathField.text)
            val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, initialFile)
            if (file != null) {
                filePathField.text = file.path
            }
        }
        filePathPanel.add(browseButton, BorderLayout.EAST)
        
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(filePathPanel, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; panel.add(JLabel("Title:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(titleField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; panel.add(JLabel("Link Reference:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(linkReferenceField, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0; panel.add(JLabel("Group:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(groupField, gbc)

        return panel
    }

    override fun doOKAction() {
        val service = project.service<JavaMethodDiagram>()
        service.updateElement(element, filePathField.text, titleField.text, groupField.text.ifBlank { null }, linkReferenceField.text)
        super.doOKAction()
    }
}

class EditRelationsDialog(private val project: Project, private val service: JavaMethodDiagram) : DialogWrapper(project) {
    private val relationsPanel = JPanel()

    init {
        title = "Edit Relations"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        
        refreshRelations()

        val scrollPane = com.intellij.ui.components.JBScrollPane(relationsPanel)
        scrollPane.preferredSize = Dimension(700, 400)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        val addButton = JButton("Add Relation")
        addButton.addActionListener {
            val nodes = service.getAllNodes().map { it.getIdentifier() }
            if (nodes.size < 2) {
                Messages.showInfoMessage(project, "Need at least 2 nodes to create a relation.", "Not Enough Nodes")
                return@addActionListener
            }
            
            val origin = nodes[0]
            val target = nodes[0]
            service.addRelation(origin, target)
            refreshRelations()
        }
        mainPanel.add(addButton, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun refreshRelations() {
        relationsPanel.removeAll()
        relationsPanel.layout = GridBagLayout()
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(2, 5, 2, 5)
        gbc.weighty = 0.0

        val nodes = service.getAllNodes().map { it.getIdentifier() }.toTypedArray()
        
        service.relations.forEachIndexed { index, relation ->
            val originCombo = com.intellij.openapi.ui.ComboBox(nodes)
            originCombo.renderer = object : com.intellij.ui.SimpleListCellRenderer<String>() {
                override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value
                    toolTipText = value
                }
            }
            originCombo.selectedItem = relation.diagramElementOrigin
            originCombo.addActionListener {
                val newOrigin = originCombo.selectedItem as String
                updateRelation(relation, newOrigin, relation.diagramElementTarget)
            }

            val targetCombo = com.intellij.openapi.ui.ComboBox(nodes)
            targetCombo.renderer = object : com.intellij.ui.SimpleListCellRenderer<String>() {
                override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value
                    toolTipText = value
                }
            }
            targetCombo.selectedItem = relation.diagramElementTarget
            targetCombo.addActionListener {
                val newTarget = targetCombo.selectedItem as String
                updateRelation(relation, relation.diagramElementOrigin, newTarget)
            }

            val removeButton = JButton(AllIcons.Actions.GC)
            removeButton.toolTipText = "Remove Relation"
            removeButton.addActionListener {
                service.removeRelation(relation)
                refreshRelations()
            }

            gbc.gridy = index
            gbc.gridx = 0
            gbc.weightx = 1.0
            relationsPanel.add(originCombo, gbc)

            gbc.gridx = 1
            gbc.weightx = 0.0
            relationsPanel.add(JLabel(" --> "), gbc)

            gbc.gridx = 2
            gbc.weightx = 1.0
            relationsPanel.add(targetCombo, gbc)

            gbc.gridx = 3
            gbc.weightx = 0.0
            relationsPanel.add(removeButton, gbc)
        }
        
        // Add a spacer at the bottom to push everything up
        val spacer = JPanel()
        gbc.gridy = service.relations.size
        gbc.gridx = 0
        gbc.gridwidth = 4
        gbc.weighty = 1.0
        relationsPanel.add(spacer, gbc)
        
        relationsPanel.revalidate()
        relationsPanel.repaint()
    }

    private fun updateRelation(oldRelation: com.helliu.drawjavacalls.model.DiagramRelation, origin: String, target: String) {
        val index = service.relations.indexOf(oldRelation)
        if (index != -1) {
            service.relations[index] = com.helliu.drawjavacalls.model.DiagramRelation(origin, target)
            service.refreshDiagramUI()
        }
    }
}
