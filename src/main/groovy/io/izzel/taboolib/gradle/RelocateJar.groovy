package io.izzel.taboolib.gradle

import groovy.transform.ToString
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.stream.Collectors

@ToString
class RelocateJar extends DefaultTask {

    @InputFile
    File inJar

    @Input
    Map<String, String> relocations

    @Optional
    @Input
    String classifier

    @Input
    Project project

    @Input
    TabooLibExtension tabooExt

    @TaskAction
    def relocate() {
        def isolated = new TreeMap<String, List<String>>()
        def mapping = relocations.collectEntries { [(it.key.replace('.', '/')), it.value.replace('.', '/')] }
        def remapper = new RelocateRemapper(relocations, mapping as Map<String, String>)
        def index = inJar.name.lastIndexOf('.')
        def name = inJar.name.substring(0, index) + (classifier == null ? "" : "-" + classifier) + inJar.name.substring(index)
        def outJar = new File(inJar.getParentFile(), name)
        def tempOut1 = File.createTempFile(name, ".jar")
        new JarOutputStream(new FileOutputStream(tempOut1)).withCloseable { out ->
            int n
            def buf = new byte[32768]
            new JarFile(inJar).withCloseable { jarFile ->
                jarFile.entries().each { def jarEntry ->
                    jarFile.getInputStream(jarEntry).withCloseable {
                        if (jarEntry.name.endsWith(".class")) {
                            def reader = new ClassReader(it)
                            def writer = new ClassWriter(0)
                            def visitor = new TabooLibClassVisitor(writer, project)
                            def rem = new ClassRemapper(visitor, remapper)
                            remapper.remapper = rem
                            reader.accept(rem, 0)
                            isolated.putAll(visitor.isolated)
                            out.putNextEntry(new JarEntry(remapper.map(jarEntry.name)))
                            out.write(writer.toByteArray())
                        } else {
                            out.putNextEntry(new JarEntry(remapper.map(jarEntry.name)))
                            while ((n = it.read(buf)) != -1) {
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        }
        def use = new TreeMap<String, Set<String>>()
        remapper.use.each {
            it.value.each { e ->
                def key = relocate(project, getNameWithOutExtension(e))
                def value = relocate(project, getNameWithOutExtension(it.key))
                use.computeIfAbsent(key) { new HashSet() }.add(value)
            }
        }
        def transfer = new TreeMap()
        isolated.each {
            transfer[relocate(project, it.key)] = it.value.stream().map { i -> relocate(project, i) }.collect(Collectors.toList())
        }
        isolated = transfer
        def tempOut2 = File.createTempFile(name, ".jar")
        new JarOutputStream(new FileOutputStream(tempOut2)).withCloseable { out ->
            int n
            def buf = new byte[32768]
            def del = new HashSet()
            def exclude = new HashSet()
            new JarFile(tempOut1).withCloseable { jarFile ->
                jarFile.entries().each { def jarEntry ->
                    jarFile.getInputStream(jarEntry).withCloseable {
                        if (jarEntry.name.endsWith(".class")) {
                            def nameWithOutExtension = getNameWithOutExtension(jarEntry.name)
                            if (use.containsKey(nameWithOutExtension.toString()) && !exclude.contains(nameWithOutExtension)) {
                                exclude.add(nameWithOutExtension)
                                if (isIsolated(use, use[nameWithOutExtension], isolated, nameWithOutExtension)) {
                                    println(" Isolated ${nameWithOutExtension}")
                                    del.add(nameWithOutExtension)
                                }
                            }
                        }
                        if (!del.contains(getNameWithOutExtension(jarEntry.name))) {
                            out.putNextEntry(new JarEntry(jarEntry.name))
                            while ((n = it.read(buf)) != -1) {
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                if (tabooExt.modules.contains("platform-bukkit")) {
                    out.putNextEntry(new JarEntry("plugin.yml"))
                    out.write(tabooExt.description.buildBukkitFile(project))
                }
                if (tabooExt.modules.contains("platform-nukkit")) {
                    out.putNextEntry(new JarEntry("nukkit.yml"))
                    out.write(tabooExt.description.buildNukkitFile(project))
                }
                if (tabooExt.modules.contains("platform-bungee")) {
                    out.putNextEntry(new JarEntry("bungee.yml"))
                    out.write(tabooExt.description.buildBungeeFile(project))
                }
                if (tabooExt.modules.contains("platform-sponge")) {
                    out.putNextEntry(new JarEntry("mcmod.info"))
                    out.write(tabooExt.description.buildSpongeFile(project))
                }
            }
        }
        Files.copy(tempOut2.toPath(), outJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    static String getNameWithOutExtension(name) {
        if (name.contains('$')) {
            return name.substring(0, name.indexOf('$')).replace('.', '/')
        } else if (name.contains('.')) {
            return name.substring(0, name.lastIndexOf('.')).replace('.', '/')
        } else {
            return name.replace('.', '/')
        }
    }

    static String relocate(Project project, String name) {
        if (name.startsWith("taboolib")) {
            return project.group.toString().replace('.', '/') + '/' + name.replace('.', '/')
        } else {
            return name.replace('.', '/')
        }
    }

    static boolean isIsolated(Map<String, Set<String>> use, Set<String> refer, Map<String, List<String>> isolated, String nameWithOutExtension) {
        if (isolated.containsKey(nameWithOutExtension)) {
            return refer.size() <= 1 || refer.stream().allMatch { nameWithOutExtension == it || isolated[nameWithOutExtension].contains(it) || isIsolated(use, use[it], isolated, it) }
        } else {
            return false
        }
    }
}
