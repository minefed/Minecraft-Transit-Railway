package org.mtr.mod.mixin;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public final class MixinCompatibilityTest {

	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
	private static final String SPEED_LIMIT_TARGET = "Lorg/mtr/core/data/PathData;getSpeedLimitMetersPerMillisecond()D";

	@Test
	public void configuredRedirectsUseScalarInjectionPointsForOlderMixinExtras() throws IOException {
		int checked = 0;
		for (String className : configuredMixins()) {
			for (MethodNode method : readClass(className).methods) {
				final AnnotationNode redirect = annotation(method, REDIRECT);
				if (redirect != null) {
					// MixinExtras 0.5.0 casts this raw ASM value to AnnotationNode before applying any redirect.
					final Object at = value(redirect, "at");
					assertTrue(at instanceof AnnotationNode, className + "." + method.name
							+ ": @Redirect.at must be a single annotation; an array crashes MixinExtras 0.5.0");
					assertEquals(AT, ((AnnotationNode) at).desc);
					checked++;
				}
			}
		}
		assertTrue(checked >= 2, "The registered speed-limit redirects must be included in this check");
	}

	@Test
	public void configuredInjectAnnotationsKeepTheirArrayEncoding() throws IOException {
		int checked = 0;
		for (String className : configuredMixins()) {
			for (MethodNode method : readClass(className).methods) {
				final AnnotationNode inject = annotation(method, INJECT);
				if (inject != null) {
					final Object at = value(inject, "at");
					assertTrue(at instanceof List, className + "." + method.name + ": @Inject.at must remain an array");
					assertFalse(((List<?>) at).isEmpty());
					for (Object entry : (List<?>) at) {
						assertTrue(entry instanceof AnnotationNode);
						assertEquals(AT, ((AnnotationNode) entry).desc);
					}
					checked++;
				}
			}
		}
		assertTrue(checked > 0, "The registered HEAD injection must be included in this check");
	}

	@Test
	public void speedLimitRedirectsRemainRegisteredAndMatchCoreInvocations() throws IOException {
		assertSpeedLimitRedirect("org/mtr/mixin/SidingSpeedLimitMixin", "mtr$capSpeedLimitForTimetable",
				"org/mtr/core/data/Siding", "generatePathDistancesAndTimeSegments");
		assertSpeedLimitRedirect("org/mtr/mixin/VehicleSpeedLimitMixin", "mtr$capSpeedLimitForVehicle",
				"org/mtr/core/data/Vehicle", "simulateMoving");
	}

	private static void assertSpeedLimitRedirect(String mixinClass, String handlerName, String coreClass, String targetMethod) throws IOException {
		assertTrue(configuredMixins().contains(mixinClass), mixinClass + " must stay registered");
		MethodNode handler = null;
		for (MethodNode method : readClass(mixinClass).methods) {
			if (method.name.equals(handlerName)) {
				handler = method;
				break;
			}
		}
		assertNotNull(handler, handlerName);
		assertEquals("(Lorg/mtr/core/data/PathData;)D", handler.desc);
		final AnnotationNode redirect = annotation(handler, REDIRECT);
		assertNotNull(redirect);
		final Object methods = value(redirect, "method");
		assertTrue(methods instanceof List && ((List<?>) methods).contains(targetMethod));
		final Object at = value(redirect, "at");
		assertTrue(at instanceof AnnotationNode, "The speed-limit redirect must use the legacy scalar encoding");
		assertEquals("INVOKE", value((AnnotationNode) at, "value"));
		assertEquals(SPEED_LIMIT_TARGET, value((AnnotationNode) at, "target"));

		boolean matched = false;
		for (MethodNode method : readClass(coreClass).methods) {
			if (method.name.equals(targetMethod)) {
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof MethodInsnNode) {
						final MethodInsnNode invocation = (MethodInsnNode) instruction;
						matched |= SPEED_LIMIT_TARGET.equals("L" + invocation.owner + ";" + invocation.name + invocation.desc);
					}
				}
			}
		}
		assertTrue(matched, coreClass + "." + targetMethod + " must still contain the redirected speed-limit call");
	}

	private static List<String> configuredMixins() throws IOException {
		final JsonObject config = JsonParser.parseString(new String(readResource("mtr.mixins.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		final String prefix = config.get("package").getAsString().replace('.', '/') + "/";
		final List<String> result = new ArrayList<>();
		for (String side : new String[]{"mixins", "client", "server"}) {
			if (config.has(side)) {
				for (JsonElement name : config.getAsJsonArray(side)) {
					result.add(prefix + name.getAsString().replace('.', '/'));
				}
			}
		}
		return result;
	}

	private static ClassNode readClass(String internalName) throws IOException {
		final ClassNode node = new ClassNode();
		new ClassReader(readResource(internalName + ".class")).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	private static AnnotationNode annotation(MethodNode method, String descriptor) {
		if (method.visibleAnnotations != null) {
			for (AnnotationNode annotation : method.visibleAnnotations) {
				if (annotation.desc.equals(descriptor)) {
					return annotation;
				}
			}
		}
		return null;
	}

	private static Object value(AnnotationNode annotation, String key) {
		if (annotation.values != null) {
			for (int i = 0; i < annotation.values.size(); i += 2) {
				if (key.equals(annotation.values.get(i))) {
					return annotation.values.get(i + 1);
				}
			}
		}
		return null;
	}

	private static byte[] readResource(String name) throws IOException {
		// The same checks can inspect the distribution JAR without loading Minecraft or applying mixins.
		final String modJar = System.getProperty("mtr.test.modJar");
		if (modJar != null && !modJar.isEmpty()) {
			try (ZipFile archive = new ZipFile(modJar)) {
				final ZipEntry entry = archive.getEntry(name);
				assertNotNull(entry, name + " is missing from " + modJar);
				try (InputStream input = archive.getInputStream(entry)) {
					return readBytes(input);
				}
			}
		}
		try (InputStream input = MixinCompatibilityTest.class.getClassLoader().getResourceAsStream(name)) {
			assertNotNull(input, name + " is missing from the test classpath");
			return readBytes(input);
		}
	}

	private static byte[] readBytes(InputStream input) throws IOException {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		int length;
		while ((length = input.read(buffer)) != -1) {
			output.write(buffer, 0, length);
		}
		return output.toByteArray();
	}
}
