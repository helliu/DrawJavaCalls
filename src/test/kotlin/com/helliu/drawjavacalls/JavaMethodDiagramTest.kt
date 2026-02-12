package com.helliu.drawjavacalls

import com.helliu.drawjavacalls.service.JavaMethodDiagram
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JavaMethodDiagramTest : BasePlatformTestCase() {

    fun testGeneratePlantUmlDiagram() {
        val service = JavaMethodDiagram(project)
        
        service.addNode("C:\\dev\\ClassA.java", "method02")
        service.addNode("C:\\dev\\ClassB.java", "method01")
        service.addNode("C:\\dev\\ClassD.java", "method03")
        
        service.addSibling("C:\\dev\\ClassF.java", "method04")
        
        val actual = service.generateDiagram().replace("\r\n", "\n").trim()
        
        assertTrue(actual.contains("state ClassA_java as \"ClassA.java\""))
        assertTrue(actual.contains("ClassA_java.method02 --> ClassB_java.method01"))
        assertTrue(actual.contains("ClassB_java.method01 --> ClassD_java.method03"))
        assertTrue(actual.contains("ClassB_java.method01 --> ClassF_java.method04"))
    }
    
    fun testPartitionGeneration() {
        val service = JavaMethodDiagram(project)
        
        service.addNode("C:\\pathA.java", "methodA", "Group1")
        service.addNode("C:\\pathB.java", "methodB", "Group1")
        service.addNode("C:\\pathC.java", "methodC", "Group2")
        service.addSibling("C:\\pathD.java", "methodD", "Group2")
        
        val actual = service.generateDiagram().replace("\r\n", "\n").trim()
        
        assertTrue(actual.contains("Group1.pathA_java.methodA"))
        assertTrue(actual.contains("Group1.pathB_java.methodB"))
        assertTrue(actual.contains("Group2.pathC_java.methodC"))
        assertTrue(actual.contains("Group2.pathD_java.methodD"))
    }
    
    fun testDeleteNode() {
        val service = JavaMethodDiagram(project)
        service.addNode("path0", "method0")
        service.addSibling("path1", "method1")
        
        assertEquals(2, service.getAllNodes().size)
        assertEquals(1, service.relations.size)
        
        // Select last node
        service.selectNodeByIndex(1)
        assertEquals("method1", service.selectedDiagramNode?.title)
        
        // Delete last node
        service.deleteSelectedNode()
        
        assertEquals("method0", service.selectedDiagramNode?.title)
        assertEquals(0, service.relations.size)
        assertEquals(1, service.getAllNodes().size)
    }

    fun testNewDiagram() {
        val service = JavaMethodDiagram(project)
        service.addNode("path0", "method0")
        assertEquals(1, service.getAllNodes().size)
        
        service.newDiagram()
        assertEquals(0, service.getAllNodes().size)
        assertEquals("", service.generateDiagram())
    }

    fun testSelectNode() {
        val service = JavaMethodDiagram(project)
        service.addNode("C:\\dev\\ClassA.java", "methodA")
        val identifier = service.getAllNodes()[0].getIdentifier()
        
        service.selectNode(identifier)
        assertEquals("methodA", service.selectedDiagramNode?.title)
    }

    fun testUpdateElement() {
        val service = JavaMethodDiagram(project)
        service.addNode("pathA.java", "methodA")
        service.addNode("pathB.java", "methodB")
        
        assertEquals(1, service.relations.size)
        assertEquals("pathA_java.methodA", service.relations[0].diagramElementOrigin)
        assertEquals("pathB_java.methodB", service.relations[0].diagramElementTarget)
        
        val nodeA = service.getAllNodes()[0]
        service.updateElement(nodeA, "newPathA.java", "newMethodA", "NewGroup", "newLinkA")
        
        assertEquals("NewGroup.newPathA_java.newMethodA", nodeA.getIdentifier())
        assertEquals("newLinkA", nodeA.linkReference)
        assertEquals(1, service.relations.size)
        assertEquals("NewGroup.newPathA_java.newMethodA", service.relations[0].diagramElementOrigin)
        assertEquals("pathB_java.methodB", service.relations[0].diagramElementTarget)
    }

    fun testAddRemoveRelation() {
        val service = JavaMethodDiagram(project)
        service.addNode("pathA.java", "methodA")
        service.addNode("pathB.java", "methodB")
        
        assertEquals(1, service.relations.size)
        
        service.addRelation("id1", "id2")
        assertEquals(2, service.relations.size)
        assertEquals("id1", service.relations[1].diagramElementOrigin)
        assertEquals("id2", service.relations[1].diagramElementTarget)
        
        service.removeRelation(service.relations[0])
        assertEquals(1, service.relations.size)
        assertEquals("id1", service.relations[0].diagramElementOrigin)
        assertEquals("id2", service.relations[0].diagramElementTarget)
    }

    fun testGenerateMermaidDiagramWithLinks() {
        val service = JavaMethodDiagram(project)
        service.diagramType = com.helliu.drawjavacalls.model.DiagramType.MERMAID
        
        // Use a simple path for this test
        service.addNode("C:/dev/ClassA.java", "methodA", "Group1")
        
        val actual = service.generateDiagram()
        
        assertTrue(actual.contains("subgraph group_"))
        assertTrue(actual.contains("[\"Group1\"]"))
        assertTrue(actual.contains("subgraph file_"))
        assertTrue(actual.contains("[\"ClassA.java\"]"))
        // If basePath is null/empty in tests, it might just use absolute path or $projectsPath/something
        // The important part is that it doesn't crash and contains the link
        assertTrue("Actual: $actual", actual.contains("click Group1.ClassA_java.methodA"))
    }

    fun testMermaidSubgraphUniqueness() {
        val service = JavaMethodDiagram(project)
        service.diagramType = com.helliu.drawjavacalls.model.DiagramType.MERMAID

        // Case from issue: subgraph a { subgraph b { subgraph AutowiredProgramatically.java { ... } } }
        service.addNode("C:\\AutowiredProgramatically.java", "test", "a.b")
        // And then: subgraph AutowiredProgramatically.java { ... }
        service.addNode("C:\\AutowiredProgramatically.java", "run", "")

        val actual = service.generateDiagram()
        
        // It should have unique identifiers for subgraphs
        // Based on the issue description, we want something like:
        // subgraph uniqueId["Name"]
        
        val subgraphs = actual.lines().filter { it.trim().startsWith("subgraph") }
        
        // We expect 4 subgraphs: "a", "b", "AutowiredProgramatically.java" (under a.b), and "AutowiredProgramatically.java" (at root)
        assertEquals(4, subgraphs.size)
        
        // Check if labels are present
        assertTrue(actual.contains("[\"a\"]"))
        assertTrue(actual.contains("[\"b\"]"))
        assertTrue(actual.contains("[\"AutowiredProgramatically.java\"]"))
        
        // Check if there are unique IDs (e.g. subgraph_1["a"])
        // If it doesn't use unique IDs, it would just be "subgraph a" or "subgraph AutowiredProgramatically.java"
        // And the issue is that "subgraph AutowiredProgramatically.java" appears twice with the same name.
        
        val nameCounts = subgraphs.map { it.substringAfter("subgraph ").substringBefore("[").trim() }.groupBy { it }.mapValues { it.value.size }
        
        nameCounts.forEach { (name, count) ->
            assertEquals("Subgraph ID '$name' is not unique", 1, count)
        }
    }

    fun testProjectPathReplacement() {
        val service = JavaMethodDiagram(project)
        val settings = com.helliu.drawjavacalls.service.DiagramSettings.getInstance(project)
        settings.useProjectRoot = true
        val projectRoot = project.basePath ?: ""
        val projectFolderName = projectRoot.substringAfterLast('\\').substringAfterLast('/')
        
        service.addNode(projectRoot + "/src/Main.java", "main", linkReference = "#main")
        val actual = service.generateDiagram()
        
        assertTrue("Link should contain \$projectsPath", actual.contains("[[\$projectsPath/$projectFolderName/src/Main.java#main main]]") || actual.contains("click Main_java.main \"\$projectsPath/$projectFolderName/src/Main.java#main\""))
    }

    fun testCustomPathReplacement() {
        val service = JavaMethodDiagram(project)
        val settings = com.helliu.drawjavacalls.service.DiagramSettings.getInstance(project)
        settings.useProjectRoot = false
        settings.customRootPath = "C:/mycustom"
        
        service.addNode("C:/mycustom/src/Main.java", "main", linkReference = "#main")
        val actual = service.generateDiagram()
        
        assertTrue("Link should contain custom path", actual.contains("[[C:/mycustom/src/Main.java#main main]]") || actual.contains("click Main_java.main \"C:/mycustom/src/Main.java#main\""))
    }

    fun testLoadDrawJavaCallDiagramPlantUml() {
        val service = JavaMethodDiagram(project)
        val projectRoot = project.basePath ?: ""
        val projectFolderName = projectRoot.substringAfterLast('\\').substringAfterLast('/')

        val puml = """'DrawJavaCalls Generated
@startuml
            
state Group1.ClassA_java.methodA as "methodA":[[${projectRoot}/ClassA.java#methodA methodA]];
state ClassA_java as "ClassA.java"

state Group1.ClassB_java.methodB as "methodB":[[${projectRoot}/ClassB.java#methodB methodB]];
state ClassB_java as "ClassB.java"

Group1.ClassA_java.methodA --> Group1.ClassB_java.methodB
@enduml
        """.trimIndent()

        service.loadDrawJavaCallDiagram(puml, com.helliu.drawjavacalls.model.DiagramType.PLANT_UML)

        assertEquals(2, service.elements.size)
        assertEquals(1, service.relations.size)
        
        assertEquals("methodA", service.elements[0].title)
        assertEquals("Group1", service.elements[0].group)
        assertEquals(projectRoot + "/ClassA.java", service.elements[0].filePath)

        assertEquals("Group1.ClassA_java.methodA", service.relations[0].diagramElementOrigin)
        assertEquals("Group1.ClassB_java.methodB", service.relations[0].diagramElementTarget)
    }

    fun testLinkReferenceInPlantUml() {
        val service = JavaMethodDiagram(project)
        service.addNode("C:/dev/ClassA.java", "methodA")
        val node = service.getAllNodes()[0]
        node.linkReference = "#customLink"
        
        val actual = service.generateDiagram()
        assertTrue(actual.contains("#customLink"))
        assertFalse(actual.contains("##customLink"))
    }

    fun testLinkReferenceInMermaid() {
        val service = JavaMethodDiagram(project)
        service.diagramType = com.helliu.drawjavacalls.model.DiagramType.MERMAID
        service.addNode("C:/dev/ClassA.java", "methodA")
        val node = service.getAllNodes()[0]
        node.linkReference = "#customLink"
        
        val actual = service.generateDiagram()
        assertTrue(actual.contains("#customLink\""))
        assertFalse(actual.contains("##customLink\""))
    }
}
