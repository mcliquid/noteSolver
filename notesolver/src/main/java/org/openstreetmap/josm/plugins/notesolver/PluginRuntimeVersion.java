package org.openstreetmap.josm.plugins.notesolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.openstreetmap.josm.plugins.PluginInformation;

/**
 * Version string from the loaded plugin JAR manifest ({@code Plugin-Version}).
 * JOSM {@link PluginInformation#version} can stay at an old update-site value after replacing the JAR.
 */
public final class PluginRuntimeVersion {

	private PluginRuntimeVersion() {
	}

	public static String resolve(PluginInformation info, Class<?> pluginClass) {
		String fromJar = readPluginVersionFromJarFile(info != null ? info.file : null);
		if (fromJar == null) {
			fromJar = readPluginVersionFromManifest(pluginClass);
		}
		if (fromJar == null && info != null && info.attr != null) {
			fromJar = info.attr.getValue("Plugin-Version");
		}
		return chooseVersion(fromJar, info != null ? info.version : null);
	}

	static String chooseVersion(String manifestVersion, String pluginInformationVersion) {
		if (manifestVersion != null && !manifestVersion.isBlank()) {
			return manifestVersion.trim();
		}
		if (pluginInformationVersion != null && !pluginInformationVersion.isEmpty()) {
			return pluginInformationVersion;
		}
		return "";
	}

	static String readPluginVersionFromJarFile(File pluginJar) {
		if (pluginJar == null || !pluginJar.isFile()) {
			return null;
		}
		try (JarFile jar = new JarFile(pluginJar)) {
			Manifest manifest = jar.getManifest();
			if (manifest == null) {
				return null;
			}
			return manifest.getMainAttributes().getValue("Plugin-Version");
		} catch (IOException ignored) {
			return null;
		}
	}

	static String readPluginVersionFromManifest(Class<?> pluginClass) {
		if (pluginClass == null) {
			return null;
		}
		try (InputStream in = pluginClass.getResourceAsStream("/META-INF/MANIFEST.MF")) {
			if (in != null) {
				return new Manifest(in).getMainAttributes().getValue("Plugin-Version");
			}
		} catch (IOException ignored) {
			// fall through
		}
		Path jarPath = jarPathFromCodeSource(pluginClass.getProtectionDomain().getCodeSource());
		if (jarPath == null) {
			return null;
		}
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			Manifest manifest = jar.getManifest();
			if (manifest == null) {
				return null;
			}
			return manifest.getMainAttributes().getValue("Plugin-Version");
		} catch (IOException ignored) {
			return null;
		}
	}

	static Path jarPathFromCodeSource(CodeSource codeSource) {
		if (codeSource == null) {
			return null;
		}
		URL location = codeSource.getLocation();
		if (location == null) {
			return null;
		}
		try {
			if ("jar".equalsIgnoreCase(location.getProtocol())) {
				JarURLConnection connection = (JarURLConnection) location.openConnection();
				URL jarFileUrl = connection.getJarFileURL();
				if (jarFileUrl == null) {
					return null;
				}
				location = jarFileUrl;
			}
			if (!"file".equalsIgnoreCase(location.getProtocol())) {
				return null;
			}
			Path path = Path.of(location.toURI());
			if (!path.toString().toLowerCase().endsWith(".jar")) {
				return null;
			}
			return path;
		} catch (Exception ignored) {
			return null;
		}
	}
}
