package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class PluginRuntimeVersionTest {

	@Test
	void chooseVersionPrefersManifestOverStalePluginInformation() {
		assertEquals("0.8.0", PluginRuntimeVersion.chooseVersion("0.8.0", "0.5.0"));
	}

	@Test
	void chooseVersionFallsBackToPluginInformationWhenManifestMissing() {
		assertEquals("0.5.0", PluginRuntimeVersion.chooseVersion(null, "0.5.0"));
		assertEquals("0.5.0", PluginRuntimeVersion.chooseVersion("  ", "0.5.0"));
	}

	@Test
	void chooseVersionEmptyWhenBothMissing() {
		assertEquals("", PluginRuntimeVersion.chooseVersion(null, null));
	}

	@Test
	@EnabledIf("distJarExists")
	void readPluginVersionFromJarFileReadsManifest() {
		assertEquals("0.8.2",
				PluginRuntimeVersion.readPluginVersionFromJarFile(distJar().toFile()));
	}

	@Test
	@EnabledIf("distJarExists")
	void resolvePrefersJarFileOverStalePluginInformationVersion() {
		String fromJar = PluginRuntimeVersion.readPluginVersionFromJarFile(distJar().toFile());
		assertEquals("0.8.2", PluginRuntimeVersion.chooseVersion(fromJar, "0.5.0"));
	}

	static boolean distJarExists() {
		return distJar().toFile().isFile();
	}

	static Path distJar() {
		return Path.of("build", "dist", "notesolver.jar");
	}
}
