// ASM: a very small and fast Java bytecode manipulation framework
// Copyright (c) 2000-2011 INRIA, France Telecom
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
// 1. Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
// 3. Neither the name of the copyright holders nor the names of its
//    contributors may be used to endorse or promote products derived from
//    this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
// THE POSSIBILITY OF SUCH DAMAGE.
package org.objectweb.asm.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.objectweb.asm.test.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.test.AsmTest;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.util.CheckMethodAdapter;

/**
 * ClassRemapper tests.
 *
 * @author Eric Bruneton
 */
public class ClassRemapperTest extends AsmTest {

  @Test
  public void testRenameClass() {
    ClassNode classNode = new ClassNode();
    ClassRemapper classRemapper =
        new ClassRemapper(classNode, new SimpleRemapper("pkg/C", "new/pkg/C"));
    classRemapper.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, "pkg/C", null, "java/lang/Object", null);
    assertEquals("new/pkg/C", classNode.name);
  }

  @Test
  public void testRenameInnerClass() {
    ClassNode classNode = new ClassNode();
    ClassRemapper remapper =
        new ClassRemapper(
            classNode,
            new Remapper() {
              @Override
              public String map(final String internalName) {
                if ("a".equals(internalName)) {
                  return "pkg/Demo";
                }
                if ("a$g".equals(internalName)) {
                  return "pkg/Demo$Container";
                }
                return internalName;
              }
            });
    remapper.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, "a", null, "java/lang/Object", null);
    remapper.visitInnerClass("a$g", "a", "g", Opcodes.ACC_PUBLIC);
    assertEquals("pkg/Demo", classNode.innerClasses.get(0).outerName);
    assertEquals("Container", classNode.innerClasses.get(0).innerName);
  }

  @Test
  public void testRenameModuleHashes() {
    ClassNode classNode = new ClassNode();
    ClassRemapper classRemapper =
        new ClassRemapper(
            classNode,
            new Remapper() {
              @Override
              public String mapModuleName(final String name) {
                return "new." + name;
              }
            });
    classRemapper.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
    classRemapper.visitAttribute(
        new ModuleHashesAttribute("algorithm", Arrays.asList("pkg.C"), Arrays.asList(new byte[0])));
    assertEquals("C", classNode.name);
    assertEquals("new.pkg.C", ((ModuleHashesAttribute) classNode.attrs.get(0)).modules.get(0));
  }

  @Test
  public void testRenameConstantDynamic() {
    ClassNode classNode = new ClassNode();
    ClassRemapper classRemapper =
        new ClassRemapper(
            Opcodes.ASM7,
            classNode,
            new Remapper() {
              @Override
              public String mapInvokeDynamicMethodName(final String name, final String descriptor) {
                return "new." + name;
              }

              @Override
              public String map(final String internalName) {
                if (internalName.equals("java/lang/String")) {
                  return "java/lang/Integer";
                }
                return internalName;
              }
            }) {
          /* inner class so it can access to the protected constructor */
        };
    classRemapper.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null);
    MethodVisitor methodVisitor =
        classRemapper.visitMethod(Opcodes.ACC_PUBLIC, "hello", "()V", null, null);
    methodVisitor.visitCode();
    methodVisitor.visitLdcInsn(
        new ConstantDynamic(
            "foo",
            "Ljava/lang/String;",
            new Handle(Opcodes.H_INVOKESTATIC, "BSMHost", "bsm", "()Ljava/lang/String;", false)));
    methodVisitor.visitInsn(Opcodes.POP);
    methodVisitor.visitMaxs(1, 1);
    methodVisitor.visitEnd();
    classRemapper.visitEnd();
    ConstantDynamic constantDynamic =
        (ConstantDynamic) ((LdcInsnNode) classNode.methods.get(0).instructions.get(0)).cst;
    assertEquals("new.foo", constantDynamic.getName());
    assertEquals("Ljava/lang/Integer;", constantDynamic.getDescriptor());
    assertEquals("()Ljava/lang/Integer;", constantDynamic.getBootstrapMethod().getDesc());
  }

  /** Tests that classes transformed with a ClassRemapper can be loaded and instantiated. */
  @ParameterizedTest
  @MethodSource(ALL_CLASSES_AND_ALL_APIS)
  public void testRemapLoadAndInstantiate(
      final PrecompiledClass classParameter, final Api apiParameter) {
    ClassReader classReader = new ClassReader(classParameter.getBytes());
    ClassWriter classWriter = new ClassWriter(0);
    UpperCaseRemapper upperCaseRemapper = new UpperCaseRemapper(classParameter.getInternalName());

    ClassRemapper classRemapper =
        new ClassRemapper(apiParameter.value(), classWriter, upperCaseRemapper);
    if (classParameter.isMoreRecentThan(apiParameter)) {
      assertThrows(RuntimeException.class, () -> classReader.accept(classRemapper, 0));
      return;
    }
    classReader.accept(classRemapper, 0);
    byte[] classFile = classWriter.toByteArray();
    assertThat(() -> loadAndInstantiate(upperCaseRemapper.getRemappedClassName(), classFile))
        .succeedsOrThrows(UnsupportedClassVersionError.class)
        .when(classParameter.isMoreRecentThanCurrentJdk());
  }

  /**
   * Tests that classes transformed with a ClassNode and ClassRemapper can be loaded and
   * instantiated.
   */
  @ParameterizedTest
  @MethodSource(ALL_CLASSES_AND_ALL_APIS)
  public void testRemapLoadAndInstantiateWithTreeApi(
      final PrecompiledClass classParameter, final Api apiParameter) {
    ClassNode classNode = new ClassNode();
    new ClassReader(classParameter.getBytes()).accept(classNode, 0);

    ClassWriter classWriter = new ClassWriter(0);
    UpperCaseRemapper upperCaseRemapper = new UpperCaseRemapper(classParameter.getInternalName());
    ClassRemapper classRemapper =
        new ClassRemapper(apiParameter.value(), classWriter, upperCaseRemapper);
    if (classParameter.isMoreRecentThan(apiParameter)) {
      assertThrows(RuntimeException.class, () -> classNode.accept(classRemapper));
      return;
    }
    classNode.accept(classRemapper);
    byte[] classFile = classWriter.toByteArray();
    assertThat(() -> loadAndInstantiate(upperCaseRemapper.getRemappedClassName(), classFile))
        .succeedsOrThrows(UnsupportedClassVersionError.class)
        .when(classParameter.isMoreRecentThanCurrentJdk());
  }

  static class UpperCaseRemapper extends Remapper {

    private final String internalClassName;
    private final String remappedInternalClassName;

    UpperCaseRemapper(final String internalClassName) {
      this.internalClassName = internalClassName;
      this.remappedInternalClassName =
          internalClassName.equals("module-info")
              ? internalClassName
              : internalClassName.toUpperCase();
    }

    String getRemappedClassName() {
      return remappedInternalClassName.replace('/', '.');
    }

    @Override
    public String mapDesc(final String descriptor) {
      checkDescriptor(descriptor);
      return super.mapDesc(descriptor);
    }

    @Override
    public String mapType(final String type) {
      if (type != null && !type.equals("module-info")) {
        checkInternalName(type);
      }
      return super.mapType(type);
    }

    @Override
    public String mapMethodName(final String owner, final String name, final String descriptor) {
      if (name.equals("<init>") || name.equals("<clinit>")) {
        return name;
      }
      return owner.equals(internalClassName) ? name.toUpperCase() : name;
    }

    @Override
    public String mapInvokeDynamicMethodName(final String name, final String descriptor) {
      return name.toUpperCase();
    }

    @Override
    public String mapFieldName(final String owner, final String name, final String descriptor) {
      return owner.equals(internalClassName) ? name.toUpperCase() : name;
    }

    @Override
    public String map(final String typeName) {
      return typeName.equals(internalClassName) ? remappedInternalClassName : typeName;
    }

    @Override
    public Object mapValue(Object value) {
      if (value instanceof Boolean
          || value instanceof Byte
          || value instanceof Short
          || value instanceof Character
          || value instanceof Integer
          || value instanceof Long
          || value instanceof Double
          || value instanceof Float
          || value instanceof String
          || value instanceof Type
          || value instanceof Handle
          || value instanceof ConstantDynamic
          || value.getClass().isArray()) {
        return super.mapValue(value);
      }
      // If this happens, add support for the new type in Remapper.mapValue(), if needed.
      throw new IllegalArgumentException("Unsupported type of value: " + value);
    }
  }

  private static void checkDescriptor(final String descriptor) {
    CheckMethodAdapter checkMethodAdapter = new CheckMethodAdapter(null);
    checkMethodAdapter.visitCode();
    checkMethodAdapter.visitFieldInsn(Opcodes.GETFIELD, "Owner", "name", descriptor);
  }

  private static void checkInternalName(final String internalName) {
    CheckMethodAdapter checkMethodAdapter = new CheckMethodAdapter(null);
    checkMethodAdapter.visitCode();
    checkMethodAdapter.visitFieldInsn(Opcodes.GETFIELD, internalName, "name", "I");
  }
}
