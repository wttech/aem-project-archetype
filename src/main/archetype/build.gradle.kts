import com.cognifide.gradle.aem.bundle.tasks.bundle
import com.cognifide.gradle.aem.common.instance.local.Source
import com.cognifide.gradle.aem.common.instance.local.OpenMode
import com.github.dkorotych.gradle.maven.exec.MavenExec

plugins {
    id("com.cognifide.aem.instance.local")
    id("com.cognifide.environment")
    id("com.neva.fork")
    id("com.github.dkorotych.gradle-maven-exec")
}

allprojects {

    group = "${groupId}"

    repositories {
        mavenLocal()
        jcenter()
        maven("https://repo.adobe.com/nexus/content/groups/public")
    }
}

defaultTasks("develop")

common {
    tasks {
        registerSequence("develop", {
            description = "Builds and deploys AEM application to instances (optionally cleans environment)"
            dependsOn(":requireProps")
        }) {
            when (prop.string("instance.type")) {
                "local" -> dependsOn(
                        ":instanceSetup",
                        ":environmentUp",
                        ":all:packageDeploy",
                        ":environmentReload",
                        ":environmentAwait"
                )
                else -> dependsOn(
                        ":instanceProvision",
                        ":all:packageDeploy"
                )
            }
        }
    }
}

aem {
    instance {
        provisioner { // https://github.com/Cognifide/gradle-aem-plugin/blob/master/docs/instance-plugin.md#task-instanceprovision
            configureReplicationAgentAuthor("publish") { enable(publishInstance) }
            configureReplicationAgentPublish("flush") { enable("http://localhost:80/dispatcher/invalidate.cache") }
            deployPackage("com.neva.felix:search-webconsole-plugin:1.3.0")
        }
    }
    localInstance {
        install {
            files {
                // https://github.com/Cognifide/gradle-aem-plugin/blob/master/docs/local-instance-plugin.md#pre-installed-osgi-bundles-and-crx-packages
            }
        }
    }
}

environment { // https://github.com/Cognifide/gradle-environment-plugin
    docker {
        containers {
            "httpd" {
                resolve {
                    resolveFiles {
                        download("http://download.macromedia.com/dispatcher/download/dispatcher-apache2.4-linux-x86_64-4.3.3.tar.gz").use {
                            copyArchiveFile(it, "**/dispatcher-apache*.so", file("modules/mod_dispatcher.so"))
                        }
                    }
                    rootProject.file("src/environment/httpd/conf.d/variables/default.vars")
                            .copyTo(rootProject.file("dispatcher/src/conf.d/variables/default.vars"), true)
                    ensureDir("htdocs", "cache", "logs")
                }
                up {
                    ensureDir("/usr/local/apache2/logs", "/var/www/localhost/htdocs", "/var/www/localhost/cache")
                    execShell("Starting HTTPD server", "/usr/sbin/httpd -k start")
                }
                reload {
                    cleanDir("/var/www/localhost/cache")
                    execShell("Restarting HTTPD server", "/usr/sbin/httpd -k restart")
                }
                dev {
                    watchRootDir(
                            "dispatcher/src/conf.d",
                            "dispatcher/src/conf.dispatcher.d",
                            "src/environment/httpd"
                    )
                }
            }
        }
    }
    hosts {
        "http://publish" { tag("publish") }
        "http://flush" { tag("flush") }
    }
    healthChecks {
        http("Site '${appTitle}'", "http://publish/us/en.html", "${appTitle}")
        http("Author Sites Editor", "http://localhost:4502/sites.html") {
            containsText("Sites")
            options { basicCredentials = aem.authorInstance.credentials }
        }
    }
}

tasks {
    instanceResolve { dependsOn(requireProps) }
    instanceCreate { dependsOn(requireProps) }
    environmentUp { mustRunAfter(instanceUp, instanceProvision, instanceSetup) }
    environmentAwait { mustRunAfter(instanceAwait) }

    register<MavenExec>("pom") {
        goals("clean", "install", "--non-recursive")
        inputs.file("pom.xml")
        outputs.dir(file(System.getProperty("user.home")).resolve(".m2/repository/${project.group.toString().replace(".", "/")}/${project.name}"))
    }
}

fork {
    properties {
        define("Instance Options", mapOf(
                "instanceType" to {
                    label = "Type"
                    select("local", "remote")
                    description = "Local - instance will be created on local file system\nRemote - connecting to remote instance only"
                    controller { toggle(value == "local", "instanceRunModes", "instanceJvmOpts", "localInstance*") }
                },
                "instanceAuthorHttpUrl" to {
                    label = "Author HTTP URL"
                    url("http://localhost:4502")
                    optional()
                    description = "For accessing AEM author instance (leave empty to skip creating it)"
                },
                "instancePublishHttpUrl" to {
                    label = "Publish HTTP URL"
                    url("http://localhost:4503")
                    optional()
                    description = "For accessing AEM publish instance (leave empty to skip creating it)"
                },
                "instanceAuthorOnly" to {
                    label = "Author Only"
                    description = "Limits instances to work with to author instance only."
                    checkbox(false)
                    controller { other("instancePublishOnly").enabled = !value.toBoolean() }
                },
                "instancePublishOnly" to {
                    label = "Publish Only"
                    description = "Limits instances to work with to publish instance only."
                    checkbox(false)
                    controller { other("instanceAuthorOnly").enabled = !value.toBoolean() }
                },
                "instanceProvisionEnabled" to {
                    label = "Provision Enabled"
                    description = "Turns on/off automated instance configuration."
                    checkbox(true)
                },
                "instanceProvisionDeployPackageStrict" to {
                    label = "Provision Deploy Package Strict"
                    description = "Check if package is actually deployed on instance.\n" +
                            "By default faster heuristic is used which does not require downloading deployed packages eagerly."
                    checkbox(false)
                }
        ))

        define("Instance Checking", mapOf(
                "instanceCheckHelpEnabled" to {
                    label = "Help"
                    description = "Tries to start bundles automatically when instance is not stable longer time."
                    checkbox(true)
                },
                "instanceCheckBundlesEnabled" to {
                    label = "Bundles"
                    description = "Awaits for all bundles in active state."
                    checkbox(true)
                },
                "instanceCheckInstallerEnabled" to {
                    label = "Installer"
                    description = "Awaits for Sling OSGi Installer not processing any resources."
                    checkbox(true)
                },
                "instanceCheckEventsEnabled" to {
                    label = "Events"
                    description = "Awaits period of time free of OSGi events incoming."
                    checkbox(true)
                },
                "instanceCheckComponentsEnabled" to {
                    label = "Components"
                    description = "Awaits for active platform and application specific components."
                    checkbox(true)
                }
        ))

        define("Local instance", mapOf(
                "localInstanceSource" to {
                    label = "Source"
                    description = "Controls how instances will be created (from scratch, backup or any available source)"
                    select(Source.values().map { it.name.toLowerCase() }, Source.AUTO.name.toLowerCase())
                },
                "localInstanceQuickstartJarUri" to {
                    label = "Quickstart URI"
                    description = "For file named 'cq-quickstart-x.x.x.jar'"
                },
                "localInstanceQuickstartLicenseUri" to {
                    label = "Quickstart License URI"
                    description = "For file named 'license.properties'"
                },
                "localInstanceBackupDownloadUri" to {
                    label = "Backup Download URI"
                    description = "For backup file. Protocols supported: SMB/SFTP/HTTP"
                    optional()
                },
                "localInstanceBackupUploadUri" to {
                    label = "Backup Upload URI"
                    description = "For directory containing backup files. Protocols supported: SMB/SFTP"
                    optional()
                },
                "localInstanceRunModes" to {
                    label = "Run Modes"
                    text("local")
                },
                "localInstanceJvmOpts" to {
                    label = "JVM Options"
                    text("-server -Xmx2048m -XX:MaxPermSize=512M -Djava.awt.headless=true")
                },
                "localInstanceOpenMode" to {
                    label = "Open Automatically"
                    description = "Open web browser when instances are up."
                    select(OpenMode.values().map { it.name.toLowerCase() }, OpenMode.ALWAYS.name.toLowerCase())
                },
                "localInstanceOpenAuthorPath" to {
                    label = "Open Author Path"
                    text("/aem/start.html")
                },
                "localInstanceOpenPublishPath" to {
                    label = "Open Publish Path"
                    text("/crx/packmgr")
                }
        ))

        define("Package", mapOf(
                "packageDeployAvoidance" to {
                    label = "Deploy Avoidance"
                    description = "Avoids uploading and installing package if identical is already deployed on instance."
                    checkbox(true)
                },
                "packageDamAssetToggle" to {
                    label = "Deploy Without DAM Worklows"
                    description = "Turns on/off temporary disablement of assets processing for package deployment time.\n" +
                            "Useful to avoid redundant rendition generation when package contains renditions synchronized earlier."
                    checkbox(true)
                    dynamic()
                },
                "packageValidatorEnabled" to {
                    label = "Validator Enabled"
                    description = "Turns on/off package validation using OakPAL."
                    checkbox(true)
                },
                "packageNestedValidation" to {
                    label = "Nested Validation"
                    description = "Turns on/off separate validation of built subpackages."
                    checkbox(true)
                },
                "packageBundleTest" to {
                    label = "Bundle Test"
                    description = "Turns on/off running tests for built bundles put under install path."
                    checkbox(true)
                }
        ))

        define("Authorization", mapOf(
                "companyUser" to {
                    label = "User"
                    description = "Authorized to access AEM files"
                    defaultValue = System.getProperty("user.name").orEmpty()
                    optional()
                },
                "companyPassword" to {
                    label = "Password"
                    description = "For above user"
                    optional()
                },
                "companyDomain" to {
                    label = "Domain"
                    description = "Needed only when accessing AEM files over SMB"
                    defaultValue = System.getenv("USERDOMAIN").orEmpty()
                    optional()
                }
        ))

        define("Other", mapOf(
                "webpackMode" to {
                    label = "Webpack Mode"
                    description = "Controls optimization of front-end resources (CSS/JS/assets) "
                    select("dev", "prod")
                },
                "notifierEnabled" to {
                    label = "Notifications"
                    description = "Controls displaying of GUI build notifications (baloons)"
                    checkbox(true)
                }
        ))
    }
}