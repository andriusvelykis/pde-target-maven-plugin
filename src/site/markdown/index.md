# Overview

This Maven plugin adds POM-first dependencies to Eclipse PDE target platform. It is intended to
help with Eclipse plugin development using [Eclipse Tycho][tycho] and PDE, when Maven OSGI
dependencies are used.

Eclipse Tycho normally uses **p2** repositories to resolve build dependencies. However, it also
allows using OSGI bundles from Maven repository (POM-first dependencies) with configuration
option `<pomDependencies>consider</pomDependencies>`
(see [Tycho POM-first dependencies][tycho-pom]). Unfortunately, the POM-first dependencies cannot
be easily included in Target Platform Definition. This means that when the Tycho target platform
is used in Eclipse PDE, it lacks the POM-first dependencies, leading to different target platforms
between Tycho and within Eclipse IDE.

The **`pde-target-maven-plugin`** aims to help with this disparity: it can augment the base Target
Definition used by Tycho with POM-first dependencies resolved against the local Maven repository.
The generated target definition file will contain original locations, but will also add _Directory_
locations to each POM dependency.

[tycho]: http://eclipse.org/tycho/
[tycho-pom]: http://wiki.eclipse.org/Tycho/How_Tos/Dependency_on_pom-first_artifacts


## Goals overview

The PDE target definition plugin only has one goal:

-   **[pde-target:add-pom-dependencies][goal-desc]** adds POM-first dependencies of the project
    to the indicated base target definition file and writes a new target definition.

[goal-desc]: add-pom-dependencies-mojo.html


## Usage

Refer to [goal description][goal-desc] for the list of all configuration options for
PDE target definition plugin. Note that the plugin reuses [Maven Dependency Plugin][dep-plugin]
so a lot of configuration options are shared with dependency goals.

```xml
<project>
  ...
  <build>
    <plugins>
      <plugin>
        <groupId>lt.velykis.maven</groupId>
        <artifactId>pde-target-maven-plugin</artifactId>
        <version>1.0.0</version>
        <executions>
          <execution>
            <id>pde-target</id>
            <goals>
              <goal>add-pom-dependencies</goal>
            </goals>
            <configuration>
              <baseDefinition>${project.basedir}/my-target.target</baseDefinition>
              <outputFile>${project.build.directory}/my-target-pde.target</outputFile>
            </configuration>
          </execution>
        </executions>
        ...
      </plugin>
      ...
    </plugins>
  </build>
  ...
</project>
```


### Set target platform

The use of generated target platform is quite straightforward with
[Eclipse Maven integration][m2e].

1.  First, import the Maven projects - this will resolve all POM dependencies into the local Maven
    repository. 
2.  The `add-pom-dependencies` goal supports [m2e][] out of the box, so the new target definition
    file will be generated automatically with resolved locations of POM-first dependencies.
3.  Finally, select the generated target definition file and _set as Target Platform_.

[dep-plugin]: http://maven.apache.org/plugins/maven-dependency-plugin/
[m2e]: http://eclipse.org/m2e/
