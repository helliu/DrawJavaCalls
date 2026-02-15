package com.helliu.drawjavacalls.service

import com.helliu.drawjavacalls.model.DiagramElement
import com.helliu.drawjavacalls.model.DiagramRelation

interface DiagramGenerator {
    fun generateDiagram(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>): String
    fun generateDiagramWithCustomRoot(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>, customRoot: String): String
    fun getExtension(): String
}

class PlantUmlGenerator : DiagramGenerator {
    override fun generateDiagram(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>): String {
        return generateDiagramInternal(project, elements, relations, null)
    }

    override fun generateDiagramWithCustomRoot(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>, customRoot: String): String {
        return generateDiagramInternal(project, elements, relations, customRoot)
    }

    private fun generateDiagramInternal(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>, customRoot: String?): String {
        if (elements.isEmpty()) return ""
        val settings = DiagramSettings.getInstance(project)
        val elementsUml = elements.joinToString("\n\n") { generateElement(it, project, settings, customRoot) }
        val relationsUml = relations.joinToString("\n") { generateRelation(it) }
        
        return """'DrawJavaCalls Generated
@startuml
            
$elementsUml
                        
$relationsUml
@enduml
        """.trimIndent()
    }

    override fun getExtension(): String = "puml"

    private fun generateElement(element: DiagramElement, project: com.intellij.openapi.project.Project, settings: DiagramSettings, customRoot: String?): String {
        val fileName = element.filePath.substringAfterLast('\\').substringAfterLast('/')
        val stateName = fileName.replace(".", "_")
        
        val linkPath = if (settings.useProjectRoot) {
            val projectRoot = customRoot ?: project.basePath ?: ""
            if (projectRoot.isNotEmpty() && element.filePath.startsWith(projectRoot)) {
                val projectFolderName = projectRoot.substringAfterLast('\\').substringAfterLast('/')
                "\$projectsPath/" + projectFolderName + element.filePath.substring(projectRoot.length).replace("\\", "/")
            } else {
                element.filePath.replace("\\", "/")
            }
        } else {
            val customRootPath = settings.customRootPath
            if (customRootPath.isNotEmpty() && element.filePath.startsWith(customRootPath)) {
                customRootPath + element.filePath.substring(customRootPath.length).replace("\\", "/")
            } else {
                element.filePath.replace("\\", "/")
            }
        }

        return """
            state ${element.getIdentifier()} as "${element.title}":[[${linkPath}${element.linkReference} ${element.title}]];
            state $stateName as "$fileName"
        """.trimIndent()
    }

    private fun generateRelation(relation: DiagramRelation): String {
        return "${relation.diagramElementOrigin} --> ${relation.diagramElementTarget}"
    }
}

class MermaidGenerator : DiagramGenerator {
    override fun generateDiagram(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>): String {
        return generateDiagramInternal(project, elements, relations, null)
    }

    override fun generateDiagramWithCustomRoot(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>, customRoot: String): String {
        return generateDiagramInternal(project, elements, relations, customRoot)
    }

    private fun generateDiagramInternal(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>, customRoot: String?): String {
        if (elements.isEmpty()) return ""
        val settings = DiagramSettings.getInstance(project)

        val sb = StringBuilder()
        sb.append("%%DrawJavaCalls Generated\n")
        sb.append("graph TD\n")

        // Grouping by group and fileName
        val root = GroupNode("")
        for (element in elements) {
            var current = root
            val groups = element.group?.split(".")?.filter { it.isNotBlank() } ?: emptyList()
            for (g in groups) {
                current = current.subGroups.getOrPut(g) { GroupNode(g) }
            }
            val fileName = element.filePath.substringAfterLast('\\').substringAfterLast('/')
            val fileNode = current.fileNodes.getOrPut(fileName) { FileNode(fileName) }
            fileNode.elements.add(element)
        }

        // Generate subgraphs
        generateSubgraphs(root, sb, "    ")

        // Relations
        for (rel in relations) {
            val originElement = elements.find { it.getIdentifier() == rel.diagramElementOrigin }
            val targetElement = elements.find { it.getIdentifier() == rel.diagramElementTarget }
            
            val originLabel = originElement?.title ?: rel.diagramElementOrigin
            val targetLabel = targetElement?.title ?: rel.diagramElementTarget
            
            sb.append("    ${rel.diagramElementOrigin}[\"$originLabel\"] --> ${rel.diagramElementTarget}[\"$targetLabel\"]\n")
        }

        // Add standalone nodes if no relations
        if (relations.isEmpty()) {
            for (element in elements) {
                sb.append("    ${element.getIdentifier()}[\"${element.title}\"]\n")
            }
        }

        // Styling and links
        sb.append("\n    %% Styling and links for nodes\n")
        for (element in elements) {
            val linkPath = if (settings.useProjectRoot) {
                val projectRoot = customRoot ?: project.basePath ?: ""
                if (projectRoot.isNotEmpty() && element.filePath.startsWith(projectRoot)) {
                    val projectFolderName = projectRoot.substringAfterLast('\\').substringAfterLast('/')
                    "\$projectsPath/" + projectFolderName + element.filePath.substring(projectRoot.length).replace("\\", "/")
                } else {
                    element.filePath.replace("\\", "/")
                }
            } else {
                val customRootPath = settings.customRootPath
                if (customRootPath.isNotEmpty() && element.filePath.startsWith(customRootPath)) {
                    customRootPath + element.filePath.substring(customRootPath.length).replace("\\", "/")
                } else {
                    element.filePath.replace("\\", "/")
                }
            }
            sb.append("    click ${element.getIdentifier()} \"${linkPath}${element.linkReference}\"\n")
        }

        return sb.toString()
    }

    private class GroupNode(val name: String) {
        val id: String = "group_${java.util.UUID.randomUUID().toString().replace("-", "_")}"
        val subGroups = mutableMapOf<String, GroupNode>()
        val fileNodes = mutableMapOf<String, FileNode>()
    }

    private class FileNode(val fileName: String) {
        val id: String = "file_${java.util.UUID.randomUUID().toString().replace("-", "_")}"
        val elements = mutableListOf<DiagramElement>()
    }

    private fun generateSubgraphs(node: GroupNode, sb: StringBuilder, indent: String) {
        for (subGroup in node.subGroups.values) {
            sb.append("${indent}subgraph ${subGroup.id}[\"${subGroup.name}\"]\n")
            generateSubgraphs(subGroup, sb, "$indent    ")
            sb.append("${indent}end\n")
        }
        for (fileNode in node.fileNodes.values) {
            sb.append("${indent}subgraph ${fileNode.id}[\"${fileNode.fileName}\"]\n")
            for (element in fileNode.elements) {
                sb.append("$indent    ${element.getIdentifier()}[\"${element.title}\"]\n")
            }
            sb.append("${indent}end\n")
        }
    }

    override fun getExtension(): String = "mmd"

    private fun findNodeName(elements: List<DiagramElement>, identifier: String): String {
        return elements.find { it.getIdentifier() == identifier }?.title ?: identifier
    }
}

class DrawIoGenerator : DiagramGenerator {
    private class FileNode(val fileName: String, val filePath: String) {
        val id: String = "file_${java.util.UUID.randomUUID().toString().replace("-", "_")}"
        val elements = mutableListOf<DiagramElement>()
    }

    private class GroupNode(val name: String) {
        val id: String = "group_${java.util.UUID.randomUUID().toString().replace("-", "_")}"
        val subGroups = mutableMapOf<String, GroupNode>()
        val fileNodes = mutableMapOf<String, FileNode>()
    }

    override fun generateDiagram(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>): String {
        return generateDiagramInternal(project, elements, relations, null)
    }

    override fun generateDiagramWithCustomRoot(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>, customRoot: String): String {
        return generateDiagramInternal(project, elements, relations, customRoot)
    }

    private fun calculateHeight(group: GroupNode, verticalSpacing: Int): Int {
        var h = 40 // header
        for (sub in group.subGroups.values) {
            h += calculateHeight(sub, verticalSpacing) + 20
        }
        for (file in group.fileNodes.values) {
            h += (file.elements.size * verticalSpacing) + 20
        }
        if (group.subGroups.isEmpty() && group.fileNodes.isEmpty()) h = 100
        return h
    }

    private fun generateXml(
        group: GroupNode,
        parentId: String,
        x: Int,
        y: Int,
        sb: StringBuilder,
        elementToId: MutableMap<String, String>,
        project: com.intellij.openapi.project.Project,
        settings: DiagramSettings,
        customRoot: String?,
        nodeWidth: Int,
        nodeHeight: Int,
        verticalSpacing: Int,
        padding: Int
    ) {
        var currentY = y
        for (sub in group.subGroups.values) {
            val subGroupId = sub.id
            val escapedName = sub.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val h = calculateHeight(sub, verticalSpacing)
            
            sb.append("        <mxCell id=\"$subGroupId\" value=\"$escapedName\" style=\"swimlane;whiteSpace=wrap;html=1;\" vertex=\"1\" parent=\"$parentId\">\n")
            sb.append("          <mxGeometry x=\"$x\" y=\"$currentY\" width=\"${nodeWidth + padding * 2}\" height=\"$h\" as=\"geometry\" />\n")
            sb.append("        </mxCell>\n")
            
            generateXml(sub, subGroupId, padding, 40, sb, elementToId, project, settings, customRoot, nodeWidth, nodeHeight, verticalSpacing, padding)
            currentY += h + 20
        }
        for (file in group.fileNodes.values) {
            val fileId = file.id
            val escapedName = file.fileName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val h = file.elements.size * verticalSpacing + 20
            
            sb.append("        <mxCell id=\"$fileId\" value=\"$escapedName\" style=\"swimlane;whiteSpace=wrap;html=1;\" vertex=\"1\" parent=\"$parentId\">\n")
            sb.append("          <mxGeometry x=\"$x\" y=\"$currentY\" width=\"${nodeWidth + padding * 2}\" height=\"${h + 20}\" as=\"geometry\" />\n")
            sb.append("        </mxCell>\n")

            file.elements.forEachIndexed { index, element ->
                val id = "node_${elementToId.size}"
                elementToId[element.getIdentifier()] = id
                
                val childX = padding
                val childY = 40 + (index * verticalSpacing)
                
                val escapedLabel = element.title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                
                val linkPath = if (settings.useProjectRoot) {
                    val projectRoot = customRoot ?: project.basePath ?: ""
                    if (projectRoot.isNotEmpty() && element.filePath.startsWith(projectRoot)) {
                        val projectFolderName = projectRoot.substringAfterLast('\\').substringAfterLast('/')
                        "\$projectsPath/" + projectFolderName + element.filePath.substring(projectRoot.length).replace("\\", "/")
                    } else {
                        element.filePath.replace("\\", "/")
                    }
                } else {
                    val customRootPath = settings.customRootPath
                    if (customRootPath.isNotEmpty() && element.filePath.startsWith(customRootPath)) {
                        customRootPath + element.filePath.substring(customRootPath.length).replace("\\", "/")
                    } else {
                        element.filePath.replace("\\", "/")
                    }
                }
                val fullLink = "file:///${linkPath}${element.linkReference}"
                val escapedLink = fullLink.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

                sb.append("        <UserObject id=\"$id\" label=\"$escapedLabel\" link=\"$escapedLink\">\n")
                sb.append("          <mxCell style=\"rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;\" vertex=\"1\" parent=\"$fileId\">\n")
                sb.append("            <mxGeometry x=\"$childX\" y=\"$childY\" width=\"$nodeWidth\" height=\"$nodeHeight\" as=\"geometry\" />\n")
                sb.append("          </mxCell>\n")
                sb.append("        </UserObject>\n")
            }
            currentY += h + 40
        }
    }

    private fun generateDiagramInternal(project: com.intellij.openapi.project.Project, elements: List<DiagramElement>, relations: List<DiagramRelation>, customRoot: String?): String {
        if (elements.isEmpty()) return ""
        val settings = DiagramSettings.getInstance(project)

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<mxfile host=\"app.diagrams.net\" modified=\"2024-01-01T00:00:00.000Z\" agent=\"DrawJavaCalls\" version=\"21.6.6\" type=\"device\">\n")
        sb.append("  <diagram id=\"draw-java-calls\" name=\"Page-1\">\n")
        sb.append("    <mxGraphModel dx=\"1000\" dy=\"1000\" grid=\"1\" gridSize=\"10\" guides=\"1\" tooltips=\"1\" connect=\"1\" arrows=\"1\" fold=\"1\" page=\"1\" pageScale=\"1\" pageWidth=\"827\" pageHeight=\"1169\" math=\"0\" shadow=\"0\">\n")
        sb.append("      <root>\n")
        sb.append("        <mxCell id=\"0\" />\n")
        sb.append("        <mxCell id=\"1\" parent=\"0\" />\n")

        val nodeWidth = 160
        val nodeHeight = 60
        val verticalSpacing = 120
        val padding = 40

        val rootGroup = GroupNode("")
        for (element in elements) {
            var current = rootGroup
            val groups = element.group?.split(".")?.filter { it.isNotBlank() } ?: emptyList()
            for (g in groups) {
                current = current.subGroups.getOrPut(g) { GroupNode(g) }
            }
            val fileName = element.filePath.substringAfterLast('\\').substringAfterLast('/')
            val fileNode = current.fileNodes.getOrPut(fileName) { FileNode(fileName, element.filePath) }
            fileNode.elements.add(element)
        }

        val elementToId = mutableMapOf<String, String>()

        generateXml(rootGroup, "1", 100, 100, sb, elementToId, project, settings, customRoot, nodeWidth, nodeHeight, verticalSpacing, padding)

        relations.forEachIndexed { index, relation ->
            val id = "edge_$index"
            val sourceId = elementToId[relation.diagramElementOrigin]
            val targetId = elementToId[relation.diagramElementTarget]

            if (sourceId != null && targetId != null) {
                sb.append("        <mxCell id=\"$id\" value=\"\" style=\"edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;\" edge=\"1\" parent=\"1\" source=\"$sourceId\" target=\"$targetId\">\n")
                sb.append("          <mxGeometry relative=\"1\" as=\"geometry\" />\n")
                sb.append("        </mxCell>\n")
            }
        }

        sb.append("      </root>\n")
        sb.append("    </mxGraphModel>\n")
        sb.append("  </diagram>\n")
        sb.append("</mxfile>")

        return sb.toString()
    }

    override fun getExtension(): String = "drawio"
}
