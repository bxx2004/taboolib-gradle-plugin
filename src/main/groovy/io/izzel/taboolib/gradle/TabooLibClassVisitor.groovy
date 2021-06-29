package io.izzel.taboolib.gradle

import org.gradle.api.Project
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

class TabooLibClassVisitor extends ClassVisitor {

    String name
    Project project
    Map<String, List<String>> isolated = new HashMap()

    TabooLibClassVisitor(ClassVisitor classVisitor, Project project) {
        super(Opcodes.ASM7, classVisitor);
        this.project = project
    }

    @Override
    void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.name = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    @Override
    AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        if (descriptor == "L${project.group.replace('.', '/')}/taboolib/common/Isolated;") {
            return new IsolatedAnnotationVisitor(super.visitAnnotation(descriptor, visible), project, name, this)
        } else if (descriptor == "Lorg/spongepowered/api/plugin/Plugin;") {
            return new SpongeAnnotationVisitor(super.visitAnnotation(descriptor, visible), project)
        } else {
            return super.visitAnnotation(descriptor, visible)
        }
    }

    @Override
    void visitEnd() {
        super.visitEnd()
    }
}