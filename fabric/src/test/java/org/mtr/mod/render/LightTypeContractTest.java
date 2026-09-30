package org.mtr.mod.render;

import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.LightType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Runs on resolved dependencies and again against the remapped distribution, not an assumed libs JAR. */
public final class LightTypeContractTest {

	private static final String LIGHT = "org/mtr/mapping/holder/LightType";
	private static final Map<String, Integer> CALL_SITES = Map.of(
			"org/mtr/mod/render/PositionAndRotation", 1,
			"org/mtr/mod/render/RenderRails", 4,
			"org/mtr/mod/render/RenderVehicles", 1,
			"org/mtr/mod/render/RenderSignalSemaphore", 1,
			"org/mtr/mod/screen/WorldMap", 1);

	@Test
	public void helpersReturnTheSameSingletonsInTheResolvedRuntime() throws Exception {
		assertSame(LightType.BLOCK, LightType.getBlockMapped());
		assertSame(LightType.SKY, LightType.getSkyMapped());
		assertSame(LightType.BLOCK.data, LightType.getBlockMapped().data);
		assertSame(LightType.SKY.data, LightType.getSkyMapped().data);
		assertNull(LightType.convert(null));
		final LightType[] values1 = LightType.values();
		final LightType[] values2 = LightType.values();
		assertNotSame(values1, values2, "Public values() must keep returning a fresh array");
		values1[0] = null;
		assertArrayEquals(new LightType[]{LightType.SKY, LightType.BLOCK}, values2);
		final Path resolved = Path.of(LightType.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertTrue(Files.isRegularFile(resolved), "Verify the actual resolved dependency archive");
		System.out.println("Resolved LightType: " + resolved + " SHA-256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(resolved))));
	}

	@Test
	public void enumConversionAndInitializersAgreeInTheInspectedArtifact() throws IOException {
		final ClassNode light = readClass(LIGHT);
		assertTrue(light.version <= Opcodes.V17, "The bundled dependency must remain Java 17 compatible");
		for (String constant : List.of("BLOCK", "SKY")) {
			final MethodNode getter = method(light, constant.equals("BLOCK") ? "getBlockMapped" : "getSkyMapped");
			final List<AbstractInsnNode> instructions = code(getter);
			assertEquals(3, instructions.size(), "The helper must have no side effects beyond convert");
			final FieldInsnNode nativeValue = assertInstanceOf(FieldInsnNode.class, instructions.get(0));
			assertEquals(Opcodes.GETSTATIC, nativeValue.getOpcode());
			final MethodInsnNode conversion = assertInstanceOf(MethodInsnNode.class, instructions.get(1));
			assertEquals(LIGHT, conversion.owner);
			assertEquals("convert", conversion.name);
			assertEquals(Opcodes.ARETURN, instructions.get(2).getOpcode());
			final List<AbstractInsnNode> init = code(method(light, "<clinit>"));
			boolean initializedFromSameNativeValue = false;
			for (int index = 2; index < init.size(); index++) {
				if (init.get(index) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC && field.owner.equals(LIGHT) && field.name.equals(constant)) {
					final MethodInsnNode constructor = assertInstanceOf(MethodInsnNode.class, init.get(index - 1));
					assertEquals(LIGHT, constructor.owner);
					assertEquals("<init>", constructor.name);
					final FieldInsnNode source = assertInstanceOf(FieldInsnNode.class, init.get(index - 2));
					assertEquals(nativeValue.owner, source.owner);
					assertEquals(nativeValue.name, source.name);
					assertEquals(nativeValue.desc, source.desc);
					initializedFromSameNativeValue = true;
				}
			}
			assertTrue(initializedFromSameNativeValue, constant);
		}
		final List<AbstractInsnNode> conversion = code(method(light, "convert"));
		assertEquals(List.of(Opcodes.ALOAD, Opcodes.IFNONNULL, Opcodes.ACONST_NULL, Opcodes.GOTO,
				Opcodes.INVOKESTATIC, Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.AALOAD, Opcodes.ARETURN),
				conversion.stream().map(AbstractInsnNode::getOpcode).toList());
		assertEquals("values", ((MethodInsnNode) conversion.get(4)).name);
		assertEquals("ordinal", ((MethodInsnNode) conversion.get(6)).name);
		assertEquals(List.of("SKY", "BLOCK"), code(method(light, "$values")).stream()
				.filter(value -> value instanceof FieldInsnNode).map(value -> ((FieldInsnNode) value).name).toList());
	}

	@Test
	public void everyChangedCallSiteStillSamplesBlockThenSkyAndCombinesTheFreshResults() throws IOException {
		for (Map.Entry<String, Integer> entry : CALL_SITES.entrySet()) {
			final ClassNode type = readClass(entry.getKey());
			assertTrue(type.version <= Opcodes.V17, entry.getKey() + " must target Java 17");
			int pairs = 0;
			for (MethodNode method : type.methods) {
				final List<String> operations = new ArrayList<>();
				boolean sampling = false;
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof FieldInsnNode field && field.owner.equals(LIGHT) && (field.name.equals("BLOCK") || field.name.equals("SKY"))) {
						operations.add(field.name);
						sampling = true;
					} else if (instruction instanceof MethodInsnNode call) {
						assertFalse(call.owner.equals(LIGHT) && (call.name.equals("getBlockMapped") || call.name.equals("getSkyMapped")), "Only the known singleton constants may be used here");
						if (sampling && call.name.equals("getLightLevel")) {
							operations.add("read");
						} else if (sampling && (call.owner.equals("org/mtr/mapping/holder/LightmapTextureManager") && call.name.equals("pack") || call.owner.equals("java/lang/Math") && call.name.equals("max"))) {
							operations.add("combine");
							assertEquals(List.of("BLOCK", "read", "SKY", "read", "combine"), operations, entry.getKey() + "." + method.name);
							operations.clear();
							sampling = false;
							pairs++;
						}
					}
				}
				assertTrue(operations.isEmpty(), "Uncombined light read in " + entry.getKey() + "." + method.name);
			}
			assertEquals(entry.getValue().intValue(), pairs, entry.getKey());
		}
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		final List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() >= 0) {
				code.add(instruction);
			}
		}
		return code;
	}

	private static MethodNode method(ClassNode type, String name) {
		return type.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
	}

	private static ClassNode readClass(String name) throws IOException {
		final String distribution = System.getProperty("mtr.test.modJar");
		final ClassNode type = new ClassNode();
		if (distribution != null && !distribution.isEmpty()) {
			try (ZipFile jar = new ZipFile(distribution)) {
				assertNotNull(jar.getEntry(name + ".class"), name + " is missing from " + distribution);
				try (InputStream input = jar.getInputStream(jar.getEntry(name + ".class"))) {
					new ClassReader(input).accept(type, 0);
				}
			}
		} else {
			try (InputStream input = LightTypeContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
				assertNotNull(input, name + " is missing from the resolved classpath");
				new ClassReader(input).accept(type, 0);
			}
		}
		return type;
	}
}
