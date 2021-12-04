package ca.jahed.rtpoet.js.generators

import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.sm.*
import ca.jahed.rtpoet.utils.RTQualifiedNameHelper
import ca.jahed.rtpoet.utils.composition.RTModelFlattener
import ca.jahed.rtpoet.utils.composition.RTSlot
import java.io.File
import java.io.FileWriter
import kotlin.math.min

class RTPlantUMLDiagramGenerator private constructor(
    private val model: RTModel,
    var currentDir: File,
    var debug: Boolean = false
) {

    init {
        currentDir.mkdirs()

        if(!currentDir.exists())
            throw RuntimeException("Error create output directory ${currentDir.absolutePath}")
    }

    private val slots: List<RTSlot> = RTModelFlattener.flatten(model)
    private val qualifiedNames = RTQualifiedNameHelper(model).get()

    companion object {
        @JvmStatic
        fun generate(model: RTModel, outputDir: String): Boolean {
            return generate(model, File(outputDir))
        }

        @JvmStatic
        fun generate(model: RTModel, outputDir: File): Boolean {
            return RTPlantUMLDiagramGenerator(model, outputDir).doGenerate()
        }
    }

    private fun doGenerate(): Boolean {
        if(slots.isEmpty()) return false

        generateClassDiagram(model)
        generateModel(model)
        return true
    }

    private fun generateModel(model: RTModel) {
        model.imports.forEach { generateModel(it) }
        generatePackage(model)
    }


    private fun generatePackage(pkg: RTPackage) {
        enterDir(pkg.name)
        pkg.packages.forEach { generatePackage(it) }
        pkg.capsules.forEach { generateCapsuleDiagrams(it) }
        leaveDir()
    }

    private fun enterDir(name: String) {
        currentDir = File(currentDir, name)
        currentDir.mkdirs()
    }

    private fun leaveDir() {
        currentDir = currentDir.parentFile
    }

    private fun writeFile(name: String, content: String) {
        val writer = FileWriter(File(currentDir, name))
        writer.write(formatOutput(content))
        writer.close()
    }

    private fun formatOutput(code: String): String {
        var output = ""
        var indentation = 0
        val bracketStack = mutableListOf<Char>()

        code.split("\n").forEach {
            val line = it.trim()
            if (line.isNotEmpty()) {
                line.toCharArray().filter { c -> c in "{}"}.forEach { c ->
                    if(c in "{") bracketStack.add(c)
                    else if(c in "}") bracketStack.removeLast()
                }

                output += "\t".repeat(min(bracketStack.size, indentation)) + line + "\n"
                indentation = bracketStack.size
            }
        }

        return output.trim()
    }

    private fun String.write(file: String) {
        writeFile(file, this)
    }

    private fun generateClassDiagram(model: RTModel) {
        """
            @startuml ${model.name}
            skinparam componentstyle uml2
            ${generateModelClassDiagram(model)}
            @enduml
        """.trimIndent().write("class.puml")
    }

    private fun generateModelClassDiagram(model: RTModel): String {
        return """
            ${model.imports.joinToString { generateModelClassDiagram(it) }}
            ${generateClassDiagramPackage(model)}
        """.trimIndent()
    }

    private fun generateClassDiagramPackage(pkg: RTPackage): String {
        return """
            package ${pkg.name} <<Folder>> {
                ${pkg.capsules.joinToString("\n") { generateClass(it) }}
                ${pkg.classes.joinToString("\n") { generateClass(it) }}
                ${pkg.packages.joinToString("\n") { generateClassDiagramPackage(it) }}
            }
        """.trimIndent()
    }

    private fun generateClass(cls: RTClass): String {
        return """
            class ${cls.name} {
                ${cls.attributes.joinToString("\n") { generateAttribute(it) }}
                ${cls.operations.joinToString("\n") { generateOperation(it) }}
            }
        """.trimIndent()
    }

    private fun generateAttribute(attr: RTAttribute): String {
        return """
            ${generateVisibilityModifier(attr.visibility)}${attr.name}: ${attr.type.name}${if(attr.replication > 1) "[${attr.replication}]" else ""}
        """.trimIndent()
    }

    private fun generateOperation(op: RTOperation): String {
        return """
            ${generateVisibilityModifier(op.visibility)}${op.name}(${op.parameters.joinToString(", ") { generateParameter(it) }})${if(op.ret != null) ": ${op.ret!!.type.name}" else ""}
        """.trimIndent()
    }

    private fun generateParameter(param: RTParameter): String {
        return """
            ${param.name}: ${param.type.name}${if(param.replication > 1) "[${param.replication}]" else ""}
        """.trimIndent()
    }

    private fun generateVisibilityModifier(v: RTVisibilityKind): String {
        return when(v) {
            RTVisibilityKind.PUBLIC -> "+"
            RTVisibilityKind.PROTECTED -> "#"
            RTVisibilityKind.PACKAGE -> "~"
            else -> "+"
        }
    }

    private fun generateCapsuleDiagrams(capsule:RTCapsule) {
        enterDir(capsule.name)
        generateCapsuleDiagram(capsule)
        if(capsule.stateMachine!=null) generateStateMachineDiagram(capsule)
        leaveDir()
    }
    private fun generateCapsuleDiagram(capsule: RTCapsule) {
        """
            @startuml ${capsule.name}-composition
            skinparam componentstyle uml2
            component ${capsule.name} {      
                ${capsule.parts.joinToString("\n") { generatePart(it) }}
                ${capsule.ports.joinToString("\n") { """port "${it.name}" as ${qualifiedNames[it]}""" }}
                ${capsule.connectors.joinToString("\n") { """${qualifiedNames[it.end1.port]} -u0)- ${qualifiedNames[it.end2.port]}""" }}
            }
            @enduml
        """.trimIndent().write("composition.puml")
    }

    private fun generatePart(part: RTCapsulePart): String {
        return """
            component ${part.name} ${if(part.plugin) "#line.dashed" else if(part.optional) "#lightgray" else ""} {
               ${part.capsule.ports.filter { !it.isInternal() }.joinToString("\n") { """port "${it.name}" as ${qualifiedNames[it]}""" }}
            }
        """.trimIndent()
    }

    private fun generateStateMachineDiagram(capsule: RTCapsule) {
        """
            @startuml ${capsule.name}-statemachine
            skinparam componentstyle uml2
            ${generateStates(capsule.stateMachine!!.states())}
            ${generateTransitions(capsule.stateMachine!!.transitions())}
            @enduml
        """.trimIndent().write("""statemachine.puml""")
    }

    private fun generateStates(states: List<RTGenericState>): String {
        return states.joinToString("\n") {
            when (it) {
                is RTPseudoState -> generatePseudostate(it)
                is RTCompositeState -> generateCompositeState(it)
                else -> generateSimpleState(it as RTState)
            }
        }
    }

    private fun generateSimpleState(state: RTState): String {
        return """
            state "${state.name}" as ${qualifiedNames[state]}
        """.trimIndent()
    }

    private fun generateCompositeState(state: RTCompositeState): String {
        return """
            state "${state.name}" as ${qualifiedNames[state]} {
                ${generateStates(state.states())}
                ${generateTransitions(state.transitions())}
            }
        """.trimIndent()
    }

    private fun generatePseudostate(state: RTPseudoState): String {
        return when (state.kind) {
            RTPseudoState.Kind.CHOICE -> """state ${qualifiedNames[state]} <<choice>>"""
            RTPseudoState.Kind.JOIN -> """state ${qualifiedNames[state]} <<join>>"""
            RTPseudoState.Kind.ENTRY_POINT -> """state ${qualifiedNames[state]} <<entryPoint>>"""
            RTPseudoState.Kind.EXIT_POINT -> """state ${qualifiedNames[state]} <<exitPoint>>"""
            else -> "" //no history, initial
        }
    }

    private fun generateTransitions(transitions: List<RTTransition>): String {
        return transitions.joinToString("\n") {"""
           ${if(it.source is RTPseudoState && (it.source as RTPseudoState).kind == RTPseudoState.Kind.INITIAL) "[*]" else qualifiedNames[it.source]} --> ${if(it.target is RTPseudoState && (it.target as RTPseudoState).kind == RTPseudoState.Kind.HISTORY) "[H*]" else qualifiedNames[it.target]} ${generateTriggerList(it)}
        """.trimIndent()}
    }

    private fun generateTriggerList(transition: RTTransition): String {
        return if(transition.triggers.isEmpty()) ""
        else """: ${transition.triggers.joinToString(",") { it.signal.name }}"""
    }

    private fun generateGuard(transition: RTTransition): String {
        return if(transition.guard != null) """[${transition.guard!!.body}]""" else ""
    }
}
