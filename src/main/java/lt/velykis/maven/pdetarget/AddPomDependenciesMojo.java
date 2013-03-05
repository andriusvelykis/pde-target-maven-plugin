/*
  Copyright 2013 Andrius Velykis

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package lt.velykis.maven.pdetarget;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.dependency.AbstractDependencyFilterMojo;
import org.apache.maven.plugin.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.artifact.AttachedArtifact;
import org.apache.maven.shared.artifact.filter.collection.AbstractArtifactsFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;
import org.sonatype.plexus.build.incremental.BuildContext;
import org.sonatype.plexus.build.incremental.DefaultBuildContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;


/**
 * Goal which generates Eclipse Target Definition file (*.target) from a given base target
 * definition and project's Maven dependencies.
 * <p>
 * The Maven dependencies are added as explicit directories in local Maven repository to the
 * target definition.
 * </p>
 * <p>
 * Note that the goal extends Maven Dependency Plugin configuration and thus provides some extra
 * configuration options not directly applicable to it.
 * </p>
 * @author Andrius Velykis
 */
@Mojo(name = "add-pom-dependencies", threadSafe = false,
      defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
      requiresDependencyResolution = ResolutionScope.TEST)
public class AddPomDependenciesMojo extends AbstractDependencyFilterMojo {

  /**
   * The output target definition file location.
   */
  @Parameter(property = "pde.target.outputFile")
  private File outputFile;

  /**
   * The project output directory reference.
   */
  @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
  private File buildDirectory;

  /**
   * The location of base target definition that will be augmented with POM dependencies.
   */
  @Parameter(property = "pde.target.baseDefinition", required = true)
  private File baseDefinition;

  /**
   * Exclude P2 artifact dependencies ({@code eclipse-plugin}, {@code eclipse-feature}, etc) from
   * the generated target definition.
   */
  @Parameter(property = "pde.target.excludeP2", defaultValue = "true")
  private boolean excludeP2;


  /**
   * The build context, for incremental build support.
   */
  @Component
  private BuildContext buildContext;


  @Override
  protected ArtifactsFilter getMarkedArtifactFilter() {
    return new P2Filter(excludeP2);
  }


  /**
   * Adds POM dependencies to the base target definition and writes into the new file.
   */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    if (buildContext == null) {
      // non-incremental context by default
      buildContext = new DefaultBuildContext();
    }

    File outputXmlFile = getOutputFile();

    if (!baseDefinition.isFile()) {
      throw new MojoFailureException("Invalid base target definition file: " + baseDefinition);
    }

    // base target definition changed - do the build (incremental)
    // also build if the target does not exist
    if (buildContext.hasDelta(baseDefinition) || !outputXmlFile.exists()) {
      try {
        addPomDependencies(baseDefinition, outputXmlFile);
      } catch (MojoExecutionException e) {
        throw e;
      } catch (MojoFailureException e) {
        throw e;
      } catch (Exception e) {
        throw new MojoExecutionException(
            "Target definition generation failed: " + e.getMessage(), e);
      }
    }
  }

  private void addPomDependencies(File baseDefinition, File outputXmlFile)
      throws MojoExecutionException, MojoFailureException, ParserConfigurationException,
      SAXException, IOException, XPathExpressionException, TransformerException {

    DocumentBuilder parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    parser.setErrorHandler(new DefaultHandler());
    Document doc = parser.parse(baseDefinition);

    XPath xPath = XPathFactory.newInstance().newXPath();
    NodeList nodes = (NodeList) xPath.evaluate(
        "/target/locations", doc.getDocumentElement(), XPathConstants.NODESET);

    if (nodes.getLength() == 0) {
      throw new MojoFailureException(
          "<locations> node cannot be found in base target definition "
          + baseDefinition.toString());
    }

    Element locationsNode = (Element) nodes.item(0);

    Collection<Artifact> pomDependencies = resolveDependencies();
    Set<String> artifactDirs = getArtifactDirs(pomDependencies);

    for (String dir : artifactDirs) {
      Element depLocationNode = createMavenArtifactLocation(doc, dir);
      locationsNode.appendChild(depLocationNode);
    }

    writeXmlFile(doc, outputXmlFile);
  }


  /**
   * Resolves Maven project dependencies.
   * 
   * @return Included dependency artifacts
   * @throws MojoExecutionException 
   */
  private Collection<Artifact> resolveDependencies() throws MojoExecutionException {
    return getResolvedDependencies(true);
  }


  private Set<String> getArtifactDirs(Collection<? extends Artifact> artifacts)
      throws IOException, MojoFailureException {
    
    Set<String> artifactDirs = new LinkedHashSet<String>();
    for (Artifact artifact : artifacts) {
      File artifactFile = artifact.getFile();
      String artifactDir = artifactFile.getParentFile().getCanonicalPath();
      artifactDirs.add(artifactDir);
      
      createSourceBundle(artifact);
    }
    
    return artifactDirs;
  }

  /**
   * Creates {@code <location />} definitions for the target definition file, which point to the
   * directories of the dependency JARs. This way allows Maven repository files to be added
   * to the target platform.
   * 
   * @param doc
   * @param artifact
   * @return XML Element for artifact location
   * @throws IOException
   */
  private Element createMavenArtifactLocation(Document doc, String artifactDir) 
      throws IOException {

    // <location path="/Users/andrius/.m2/repository/org/scala-lang/scala-library/2.10.0" type="Directory"/>
    Element locNode = doc.createElement("location");
    locNode.setAttribute("path", artifactDir);
    locNode.setAttribute("type", "Directory");

    return locNode;
  }

  private void writeXmlFile(Document doc, File outputXmlFile)
      throws TransformerException, IOException {

    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");

    if (!outputXmlFile.getParentFile().exists()) {
      outputXmlFile.getParentFile().mkdirs();
    }

    OutputStream outputXmlStream = buildContext.newFileOutputStream(outputXmlFile);

    try {

      Result output = new StreamResult(outputXmlStream);
      Source input = new DOMSource(doc);

      transformer.transform(input, output);
    } finally {
      outputXmlStream.close();
    }
  }
  
  private File getOutputFile() {
    return outputFile != null ? outputFile : createOutputFileName();
  }

  private File createOutputFileName() {
    String baseDefinitionName = baseDefinition.getName();

    int extIndex = baseDefinitionName.lastIndexOf(".");
    if (extIndex >= 0) {
      baseDefinitionName = baseDefinitionName.substring(0, extIndex);
    }

    // append "-pde" to the output definition and use .target extension
    String outputDefinitionName = baseDefinitionName + "-pde.target";

    // add to the root of build directory
    return new File(buildDirectory, outputDefinitionName);
  }
  
  
  private static String BUNDLE_ID_KEY = "Bundle-SymbolicName";
  private static String BUNDLE_VERSION_KEY = "Bundle-Version";
  private static String BUNDLE_VENDOR_KEY = "Bundle-Vendor";
  private static String BUNDLE_NAME_KEY = "Bundle-Name";
  
  private void createSourceBundle(Artifact artifact) throws IOException, MojoFailureException {
    
    File sourcesFile = getSourcesFile(artifact);
    if (!sourcesFile.exists()) {
      this.getLog().warn(
          "Sources jar is missing for artifact "
          + ArtifactUtils.key(artifact) + " at " + sourcesFile);
      return;
    }
    
    File sourcesBundleFile = getSourceBundleFile(sourcesFile);
    if (sourcesBundleFile.exists()) {
      this.getLog().info(
          "Skipping: sources bundle already generated for artifact "
          + ArtifactUtils.key(artifact) + " at " + sourcesBundleFile);
    }
    
    // read bundle information from the artifact's manifest
    File artifactFile = artifact.getFile();
    
    JarFile artifactJar = new JarFile(artifactFile);
    Manifest manifest = artifactJar.getManifest();
    if (manifest == null) {
      throw new MojoFailureException("Manifest is missing for artifact " + artifactFile);
    }
    
    Attributes attrs = manifest.getMainAttributes();
    String bundleId = attrs.getValue(BUNDLE_ID_KEY);
    String bundleVersion = attrs.getValue(BUNDLE_VERSION_KEY);
    String bundleVendor = attrs.getValue(BUNDLE_VENDOR_KEY);
    String bundleName = attrs.getValue(BUNDLE_NAME_KEY);
    
    
    if (bundleId == null) {
      this.getLog().warn("Bundle symbolic name cannot be resolved for " + ArtifactUtils.key(artifact));
      return;
    }
    
    if (bundleVersion == null) {
      this.getLog().warn("Bundle version cannot be resolved for " + ArtifactUtils.key(artifact));
      return;
    }
    

    // copy manifest from sources jar and add OSGI information
    JarFile sourceJar = new JarFile(sourcesFile);
    Manifest sourceManifest = sourceJar.getManifest();
    
    if (sourceManifest == null) {
      sourceManifest = new Manifest();
    }
    
    // adapt OSGI information from the original artifact
    Attributes sourceAttrs = sourceManifest.getMainAttributes();
    sourceAttrs.putValue(BUNDLE_ID_KEY, bundleId + ".source");
    sourceAttrs.putValue(BUNDLE_VERSION_KEY, bundleVersion);
    sourceAttrs.putValue("Bundle-ManifestVersion", "2");
    
    if (bundleVendor != null) {
      sourceAttrs.putValue(BUNDLE_VENDOR_KEY, bundleVendor);
    }
    
    if (bundleName != null) {
      sourceAttrs.putValue(BUNDLE_NAME_KEY, bundleName + " Source");
    }
    
    // mark this bundle as a source for the main artifact
    String sourceRef = bundleId + ";version=\"" + bundleVersion + "\";roots:=\".\"";
    sourceAttrs.putValue("Eclipse-SourceBundle", sourceRef);
    
    // copy JAR file contents with the new manifest
    copyJarFile(sourceJar, sourceManifest, sourcesBundleFile);
  }
  
  private File getSourcesFile(Artifact artifact) {
    // resolve sources as attached artifact
    Artifact sourceArtifact = new AttachedArtifact(
        artifact, "jar", "sources", artifact.getArtifactHandler());
    String sourceArtifactFileName = DependencyUtil.getFormattedFileName(sourceArtifact, false);

    return new File(artifact.getFile().getParentFile(), sourceArtifactFileName);
  }
  
  private File getSourceBundleFile(File sourceFile) {
    String sourceFileName = sourceFile.getName();
    if (sourceFileName.endsWith(".jar")) {
      sourceFileName = sourceFileName.substring(0, sourceFileName.length() - 4);
    }
    
    String sourceBundleName = sourceFileName + "-bundle.jar";
    return new File(sourceFile.getParentFile(), sourceBundleName);
  }
  
  /**
   * Copies JAR contents with new Manifest file.
   * 
   * Adapted from http://javahowto.blogspot.co.uk/2011/07/how-to-programmatically-copy-jar-files.html
   */
  private static void copyJarFile(JarFile jarFile, Manifest manifest, File destFile)
      throws IOException {

    JarOutputStream jos = new JarOutputStream(new FileOutputStream(destFile), manifest);
    Enumeration<JarEntry> entries = jarFile.entries();

    try {
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        
        if ("META-INF/MANIFEST.MF".equals(entry.getName())) {
          // skip manifest - a new one is used
          continue;
        }
        
        InputStream is = jarFile.getInputStream(entry);

        // jos.putNextEntry(entry);
        // create a new entry to avoid ZipException: invalid entry compressed size
        jos.putNextEntry(new JarEntry(entry.getName()));
        byte[] buffer = new byte[4096];
        int bytesRead = 0;
        while ((bytesRead = is.read(buffer)) != -1) {
          jos.write(buffer, 0, bytesRead);
        }
        is.close();
        jos.flush();
        jos.closeEntry();
      }
    } finally {
      jos.close();
    }
  }
  
  
  private static class P2Filter extends AbstractArtifactsFilter {

    private final boolean filterP2;

    public P2Filter(boolean filterP2) {
      this.filterP2 = filterP2;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Set<?> filter(Set artifacts) throws ArtifactFilterException {

      if (!filterP2) {
        // allow all
        return artifacts;
      } else {
        // exclude group IDs starting with "p2."
        Set<Artifact> filtered = new HashSet<Artifact>();

        for (Object artifactObj : artifacts) {
          if (!((Artifact) artifactObj).getGroupId().startsWith("p2.")) {
            filtered.add((Artifact) artifactObj);
          }
        }

        return filtered;
      }
    }

  }
}
