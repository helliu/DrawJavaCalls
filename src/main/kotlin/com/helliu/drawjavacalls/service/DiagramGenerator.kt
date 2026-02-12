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
