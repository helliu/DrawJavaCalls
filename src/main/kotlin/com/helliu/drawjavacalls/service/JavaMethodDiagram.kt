package com.helliu.drawjavacalls.service

import com.helliu.drawjavacalls.model.DiagramElement
import com.helliu.drawjavacalls.model.DiagramRelation
import com.helliu.drawjavacalls.model.DiagramType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class JavaMethodDiagram(val project: Project) {
    val elements = mutableListOf<DiagramElement>()
    val relations = mutableListOf<DiagramRelation>()
    var selectedDiagramNode: DiagramElement? = null
    var currentFilePath: String? = null
    
    var diagramType: DiagramType
        get() = DiagramSettings.getInstance(project).diagramType
        set(value) {
            DiagramSettings.getInstance(project).diagramType = value
        }

    interface DiagramChangeListener {
        fun onDiagramChanged()
    }

    private val listeners = mutableListOf<DiagramChangeListener>()

    fun addChangeListener(listener: DiagramChangeListener) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it.onDiagramChanged() }
    }

    fun addNode(filePath: String, title: String, group: String? = null, linkReference: String? = null) {
        val newNode = DiagramElement(filePath = filePath, title = title, group = group)
        if (linkReference != null) {
            newNode.linkReference = linkReference
        }
        val parentNode = selectedDiagramNode ?: elements.lastOrNull()
        elements.add(newNode)
        
        if (parentNode != null) {
            relations.add(DiagramRelation(parentNode.getIdentifier(), newNode.getIdentifier()))
        }
        
        selectedDiagramNode = newNode
        notifyChanged()
    }

    fun addSibling(filePath: String, title: String, group: String? = null, linkReference: String? = null) {
        val newNode = DiagramElement(filePath = filePath, title = title, group = group)
        if (linkReference != null) {
            newNode.linkReference = linkReference
        }
        val selected = selectedDiagramNode ?: elements.lastOrNull()
        val lastRelation = if (selected != null) {
            relations.findLast { it.diagramElementTarget == selected.getIdentifier() }
        } else null

        elements.add(newNode)
        
        if (lastRelation != null) {
            relations.add(DiagramRelation(lastRelation.diagramElementOrigin, newNode.getIdentifier()))
        } else if (selected != null) {
            // If no incoming relation to selected, but selected exists, 
            // maybe it was the root or manually added. We'll branch from it anyway.
            relations.add(DiagramRelation(selected.getIdentifier(), newNode.getIdentifier()))
        }
        
        selectedDiagramNode = newNode
        notifyChanged()
    }

    fun findNodeByIdentifier(identifier: String): DiagramElement? {
        return elements.find { it.getIdentifier() == identifier }
    }

    fun getAllNodes(): List<DiagramElement> {
        return elements
    }

    fun selectNodeByIndex(index: Int) {
        if (index in elements.indices) {
            selectedDiagramNode = elements[index]
            notifyChanged()
        }
    }

    fun selectNode(identifier: String) {
        val node = findNodeByIdentifier(identifier)
        if (node != null) {
            selectedDiagramNode = node
            notifyChanged()
        }
    }

    fun generateDiagram(customProjectRoot: String? = null): String {
        val generator = when (diagramType) {
            DiagramType.PLANT_UML -> PlantUmlGenerator()
            DiagramType.MERMAID -> MermaidGenerator()
            DiagramType.DRAW_IO -> DrawIoGenerator()
        }
        
        // Temporarily override project root if provided
        if (customProjectRoot != null && DiagramSettings.getInstance(project).useProjectRoot) {
             return generator.generateDiagramWithCustomRoot(project, elements, relations, customProjectRoot, selectedDiagramNode)
        }

        return generator.generateDiagram(project, elements, relations, selectedDiagramNode)
    }


    fun getGenerator(): DiagramGenerator {
        return when (diagramType) {
            DiagramType.PLANT_UML -> PlantUmlGenerator()
            DiagramType.MERMAID -> MermaidGenerator()
            DiagramType.DRAW_IO -> DrawIoGenerator()
        }
    }

    fun newDiagram() {
        elements.clear()
        relations.clear()
        selectedDiagramNode = null
        currentFilePath = null
        notifyChanged()
    }

    fun deleteSelectedNode() {
        val nodeToDelete = selectedDiagramNode ?: return
        val identifier = nodeToDelete.getIdentifier()
        elements.remove(nodeToDelete)
        relations.removeIf { it.diagramElementOrigin == identifier || it.diagramElementTarget == identifier }
        selectedDiagramNode = elements.lastOrNull()
        notifyChanged()
    }

    fun updateElement(element: DiagramElement, newFilePath: String, newTitle: String, newGroup: String?, newLinkReference: String) {
        val oldIdentifier = element.getIdentifier()
        element.filePath = newFilePath
        element.title = newTitle
        element.group = newGroup
        element.linkReference = newLinkReference
        val newIdentifier = element.getIdentifier()

        if (oldIdentifier != newIdentifier) {
            // Update relations that used the old identifier
            for (i in relations.indices) {
                val rel = relations[i]
                var updated = false
                var origin = rel.diagramElementOrigin
                var target = rel.diagramElementTarget

                if (origin == oldIdentifier) {
                    origin = newIdentifier
                    updated = true
                }
                if (target == oldIdentifier) {
                    target = newIdentifier
                    updated = true
                }

                if (updated) {
                    relations[i] = DiagramRelation(origin, target)
                }
            }
        }
        notifyChanged()
    }

    fun addRelation(origin: String, target: String) {
        relations.add(DiagramRelation(origin, target))
        notifyChanged()
    }

    fun removeRelation(relation: DiagramRelation) {
        relations.remove(relation)
        notifyChanged()
    }

    fun refreshDiagramUI() {
        notifyChanged()
    }

    fun loadDrawJavaCallDiagram(content: String, type: DiagramType) {
        elements.clear()
        relations.clear()
        selectedDiagramNode = null

        when (type) {
            DiagramType.PLANT_UML -> reverseEngineerPlantUml(content)
            DiagramType.MERMAID -> reverseEngineerMermaid(content)
            DiagramType.DRAW_IO -> reverseEngineerDrawIo(content)
        }

        notifyChanged()
    }

    private fun reverseEngineerDrawIo(content: String) {
        val projectRoot = project.basePath ?: ""
        val projectFolderName = projectRoot.replace("\\", "/").substringAfterLast('/')

        // Extract nodes: <UserObject id="..." label="..." link="...">
        val nodeRegex = """<UserObject\s+id="([^"]+)"\s+label="([^"]+)"\s+link="([^"]+)">""".toRegex()
        val idToIdentifier = mutableMapOf<String, String>()

        nodeRegex.findAll(content).forEach { match ->
            val id = match.groupValues[1]
            val title = match.groupValues[2].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            val link = match.groupValues[3].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

            // link is file:///path#reference or file:///path:line
            var filePathRaw = link.removePrefix("file://")
            if (filePathRaw.startsWith("/")) filePathRaw = filePathRaw.substring(1)
            
            val linkReference = if (filePathRaw.contains("#")) "#" + filePathRaw.substringAfter("#")
                               else if (filePathRaw.contains(":")) {
                                   val lastColonIndex = filePathRaw.lastIndexOf(":")
                                   if (lastColonIndex > 1) ":" + filePathRaw.substring(lastColonIndex + 1) else ""
                               } else ""
            
            var filePath = if (filePathRaw.contains("#")) filePathRaw.substringBefore("#")
                           else if (filePathRaw.contains(":")) {
                               val lastColonIndex = filePathRaw.lastIndexOf(":")
                               if (lastColonIndex > 1) filePathRaw.substring(0, lastColonIndex) else filePathRaw
                           } else filePathRaw

            if (filePath.startsWith("\$projectsPath/")) {
                filePath = filePath.replace("\$projectsPath/$projectFolderName", projectRoot)
            }
            
            val element = DiagramElement(filePath = filePath.replace("/", "\\"), title = title, linkReference = linkReference)
            if (!elements.any { it.getIdentifier() == element.getIdentifier() }) {
                elements.add(element)
            }
            idToIdentifier[id] = element.getIdentifier()
        }

        // Extract relations: <mxCell ... source="..." target="...">
        val relationRegex = """<mxCell\s+[^>]*edge="1"[^>]*source="([^"]+)"\s+target="([^"]+)"[^>]*>""".toRegex()
        relationRegex.findAll(content).forEach { match ->
            val sourceId = match.groupValues[1]
            val targetId = match.groupValues[2]
            val origin = idToIdentifier[sourceId]
            val target = idToIdentifier[targetId]
            if (origin != null && target != null) {
                if (!relations.any { it.diagramElementOrigin == origin && it.diagramElementTarget == target }) {
                    relations.add(DiagramRelation(origin, target))
                }
            }
        }
    }

    private fun reverseEngineerPlantUml(content: String) {
        val lines = content.lines()
        val projectRoot = project.basePath ?: ""
        val projectFolderName = projectRoot.substringAfterLast('\\').substringAfterLast('/')

        // Extract elements: state identifier as "title":[[linkPath#title title]];
        // For non-java, it might be linkPath:lineNumber
        val elementRegex = """state\s+([^\s]+)\s+as\s+"([^"]+)":\[\[([^\]]+)\]\];""".toRegex()

        lines.forEach { line ->
            val match = elementRegex.find(line.trim())
            if (match != null) {
                val identifier = match.groupValues[1]
                val title = match.groupValues[2]
                val linkContent = match.groupValues[3] // e.g. "path#method method" or "path:line title"
                
                val linkPathWithRef = linkContent.substringBeforeLast(" ")
                val filePathRaw = if (linkPathWithRef.contains("#")) linkPathWithRef.substringBefore("#") 
                                 else if (linkPathWithRef.contains(":")) {
                                     // Be careful with Windows drive letters like C:\
                                     val lastColonIndex = linkPathWithRef.lastIndexOf(":")
                                     if (lastColonIndex > 1) linkPathWithRef.substring(0, lastColonIndex) else linkPathWithRef
                                 }
                                 else linkPathWithRef
                
                val linkReference = if (linkPathWithRef.contains("#")) "#" + linkPathWithRef.substringAfter("#")
                                   else if (linkPathWithRef.contains(":")) {
                                       val lastColonIndex = linkPathWithRef.lastIndexOf(":")
                                       if (lastColonIndex > 1) ":" + linkPathWithRef.substring(lastColonIndex + 1) else ""
                                   }
                                   else ""

                var filePath = filePathRaw
                
                if (filePath.startsWith("\$projectsPath/")) {
                    filePath = filePath.replace("\$projectsPath/$projectFolderName", projectRoot)
                }

                // Correctly extract group by removing the fileName part and title part from identifier
                val fileName = filePath.substringAfterLast('\\').substringAfterLast('/')
                val stateName = fileName.replace(".", "_")
                
                val group = if (identifier.endsWith(".$stateName.$title")) {
                    identifier.substringBefore(".$stateName.$title").takeIf { it.isNotEmpty() }
                } else if (identifier.endsWith(".$title")) {
                    // Check if identifier is just stateName.title (no group)
                    if (identifier == "$stateName.$title") {
                        null
                    } else {
                        // This shouldn't happen with our generator, but for robustness:
                        identifier.substringBefore(".$title").substringBeforeLast(".", "").takeIf { it.isNotEmpty() }
                    }
                } else {
                    null
                }

                if (!elements.any { it.getIdentifier() == identifier }) {
                    elements.add(DiagramElement(filePath = filePath, title = title, group = group, linkReference = linkReference))
                }
            }
        }

        // Extract relations: origin --> target
        val relationRegex = """([^\s]+)\s+-->\s+([^\s]+)""".toRegex()
        lines.forEach { line ->
            val match = relationRegex.find(line.trim())
            if (match != null) {
                val origin = match.groupValues[1]
                val target = match.groupValues[2]
                relations.add(DiagramRelation(origin, target))
            }
        }
    }

    private fun reverseEngineerMermaid(content: String) {
        val lines = content.lines()
        val projectRoot = project.basePath ?: ""
        val projectFolderName = projectRoot.substringAfterLast('\\').substringAfterLast('/')

        // In Mermaid, we have identifiers and labels: id["label"]
        // And links: click id "linkPath#title" or "linkPath:lineNumber"
        val idToElement = mutableMapOf<String, DiagramElement>()
        val nodeRegex = """([^\s\[]+)\["([^"]+)"\]""".toRegex()
        val linkRegex = """click\s+([^\s]+)\s+"([^"]+)"""".toRegex()

        // First pass: find all nodes and their labels (titles)
        lines.forEach { line ->
            nodeRegex.findAll(line).forEach { match ->
                val id = match.groupValues[1]
                val title = match.groupValues[2]
                // We don't have filePath yet, will get from link
                idToElement[id] = DiagramElement(filePath = "", title = title)
            }
        }

        // Second pass: find links to get filePaths and groups
        lines.forEach { line ->
            val match = linkRegex.find(line.trim())
            if (match != null) {
                val id = match.groupValues[1]
                val linkPathWithRef = match.groupValues[2]
                
                val filePathRaw = if (linkPathWithRef.contains("#")) linkPathWithRef.substringBefore("#") 
                                 else if (linkPathWithRef.contains(":")) {
                                     val lastColonIndex = linkPathWithRef.lastIndexOf(":")
                                     if (lastColonIndex > 1) linkPathWithRef.substring(0, lastColonIndex) else linkPathWithRef
                                 }
                                 else linkPathWithRef
                
                val linkReference = if (linkPathWithRef.contains("#")) "#" + linkPathWithRef.substringAfter("#")
                                   else if (linkPathWithRef.contains(":")) {
                                       val lastColonIndex = linkPathWithRef.lastIndexOf(":")
                                       if (lastColonIndex > 1) ":" + linkPathWithRef.substring(lastColonIndex + 1) else ""
                                   }
                                   else ""

                var filePath = filePathRaw
                
                if (filePath.startsWith("\$projectsPath/")) {
                    filePath = filePath.replace("\$projectsPath/$projectFolderName", projectRoot)
                }

                val element = idToElement[id]
                if (element != null) {
                    element.filePath = filePath
                    element.linkReference = linkReference
                }
            }
        }

        idToElement.forEach { (id, element) ->
            if (element.filePath.isNotEmpty()) {
                val fileName = element.filePath.substringAfterLast('\\').substringAfterLast('/')
                val stateName = fileName.replace(".", "_")
                val title = element.title
                
                val group = if (id.endsWith(".$stateName.$title")) {
                    id.substringBefore(".$stateName.$title").takeIf { it.isNotEmpty() }
                } else if (id.endsWith(".$title")) {
                    if (id == "$stateName.$title") {
                        null
                    } else {
                        id.substringBefore(".$title").substringBeforeLast(".", "").takeIf { it.isNotEmpty() }
                    }
                } else {
                    null
                }
                element.group = group
                elements.add(element)
            }
        }

        // Extract relations: origin["label"] --> target["label"]
        // MermaidGenerator uses: sb.append("    ${rel.diagramElementOrigin}[\"$originLabel\"] --> ${rel.diagramElementTarget}[\"$targetLabel\"]\n")
        val relationRegex = """([^\s\[]+)\["[^"]+"\]\s+-->\s+([^\s\[]+)\["[^"]+"\]""".toRegex()
        lines.forEach { line ->
            val match = relationRegex.find(line.trim())
            if (match != null) {
                val origin = match.groupValues[1]
                val target = match.groupValues[2]
                relations.add(DiagramRelation(origin, target))
            }
        }
    }
}
