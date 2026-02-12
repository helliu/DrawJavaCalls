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
        }
        
        // Temporarily override project root if provided
        if (customProjectRoot != null && DiagramSettings.getInstance(project).useProjectRoot) {
             return generator.generateDiagramWithCustomRoot(project, elements, relations, customProjectRoot)
        }

        return generator.generateDiagram(project, elements, relations)
    }

    fun getGenerator(): DiagramGenerator {
        return when (diagramType) {
            DiagramType.PLANT_UML -> PlantUmlGenerator()
            DiagramType.MERMAID -> MermaidGenerator()
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

        if (type == DiagramType.PLANT_UML) {
            reverseEngineerPlantUml(content)
        } else {
            reverseEngineerMermaid(content)
        }

        notifyChanged()
    }

    private fun reverseEngineerPlantUml(content: String) {
        val lines = content.lines()
        val projectRoot = project.basePath ?: ""
        val projectFolderName = projectRoot.substringAfterLast('\\').substringAfterLast('/')

        // Extract elements: state identifier as "title":[[linkPath#title title]];
        val elementRegex = """state\s+([^\s]+)\s+as\s+"([^"]+)":\[\[([^#]+)#([^\s]+)\s+[^\]]+\]\];""".toRegex()
        val stateToGroup = mutableMapOf<String, String?>()

        lines.forEach { line ->
            val match = elementRegex.find(line.trim())
            if (match != null) {
                val identifier = match.groupValues[1]
                val title = match.groupValues[2]
                var filePath = match.groupValues[3]
                
                if (filePath.startsWith("\$projectsPath/")) {
                    filePath = filePath.replace("\$projectsPath/$projectFolderName", projectRoot)
                }

                val group = if (identifier.contains(".")) {
                    val base = identifier.substringBeforeLast(".")
                    if (base.contains(".")) {
                        base.substringBeforeLast(".")
                    } else {
                        null
                    }
                } else {
                    null
                }

                if (!elements.any { it.getIdentifier() == identifier }) {
                    elements.add(DiagramElement(filePath = filePath, title = title, group = group))
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
        // And links: click id "linkPath#title"
        val idToElement = mutableMapOf<String, DiagramElement>()
        val nodeRegex = """([^\s\[]+)\["([^"]+)"\]""".toRegex()
        val linkRegex = """click\s+([^\s]+)\s+"([^#]+)#([^"]+)"""".toRegex()

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
                var filePath = match.groupValues[2]
                
                if (filePath.startsWith("\$projectsPath/")) {
                    filePath = filePath.replace("\$projectsPath/$projectFolderName", projectRoot)
                }

                val element = idToElement[id]
                if (element != null) {
                    element.filePath = filePath
                    // Re-calculate group from id if possible, but Mermaid ids are often generated UUIDs
                    // Actually, the identifier we use in getIdentifier() might be what Mermaid uses if it's not a subgraph
                    // Wait, MermaidGenerator uses element.getIdentifier() for relations and links!
                }
            }
        }

        // Extract group from structure if possible. 
        // In MermaidGenerator, groups are subgraphs. 
        // This is getting complex. Let's see if we can simplify.
        // Actually, MermaidGenerator uses element.getIdentifier() as the node ID in relations and links.
        // So 'id' in `click id` IS the identifier.

        idToElement.forEach { (id, element) ->
            if (element.filePath.isNotEmpty()) {
                val group = if (id.contains(".")) {
                    val base = id.substringBeforeLast(".")
                    if (base.contains(".")) {
                        base.substringBeforeLast(".")
                    } else {
                        null
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
