plugins {
    id("java")
    id("idea")
    id("net.minecraftforge.gradle") version "[6.0,6.2)"
    id("org.parchmentmc.librarian.forgegradle") version "1.+"
}

val minecraftVersion: String = "1.20.1"
val forgeVersion: String = "47.4.6"
val parchmentVersion: String = "2023.09.03-1.20.1"
val tfcVersion: String = "6990923"
val ieVersion: String = "1.20.1-10.2.0-182"

val modId: String = "tfcie_prospecting"

base {
    archivesName.set("tfcie_prospecting-$minecraftVersion")
    group = "com.tfcie.prospecting"
    version = "0.1.0"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

idea {
    module {
        excludeDirs.add(file("run"))
    }
}

repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://maven.blamejared.com") // Immersive Engineering
    maven(url = "https://www.cursemaven.com") {
        content {
            includeGroup("curse.maven")
        }
    }
}

dependencies {
    minecraft("net.minecraftforge", "forge", version = "$minecraftVersion-$forgeVersion")

    // TerraFirmaCraft（群峦传说）1.20.1
    implementation(fg.deobf("curse.maven:tfc-302973:$tfcVersion"))

    // Immersive Engineering（沉浸工程）1.20.1
    implementation(fg.deobf("blusunrize.immersiveengineering:ImmersiveEngineering:$ieVersion"))

    // 阶段 1 单测（ProspectingResult / 枚举不依赖 MC 运行时，可脱离 Forge 环境测试）
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.10.1")

    // Numen ① 层 Lua API 的编译期契约。numen 的 api 制品（`-api.jar`）由 Numen 构建产出，
    // 这里只声明 compileOnly：编译时读签名，运行时由已装载的 numen 模组提供这些类，
    // 绝不打包进本模组（numen 缺席时由 NumenLuaBridge 探测后跳过，不加载任何相关类）。
    //
    // Numen 源码根可通过 `-PnumenRoot=<路径>` 或环境变量 `NUMEN_ROOT` 指定；
    // 缺省沿用本机开发工作区路径。干净 clone 时请先把 Numen 仓库放到任意位置，
    // 并按 README 用 -PnumenRoot 指过去。
    val numenRoot: File = (findProperty("numenRoot") as String?)
            ?.let { file(it) }
            ?: (System.getenv("NUMEN_ROOT")?.let { file(it) })
            ?: file("H:/VoyagerMCP/llm_in_minecraft_mod/minecraft-numen-1.20.1-new/minecraft-numen-1.20.1")
    val numenApiJar = numenRoot.resolve("numen-api/forge/build/libs/numen_api-forge-1.20.1-0.1.5-api.jar")
    if (numenApiJar.exists()) {
        compileOnly(files(numenApiJar))
        // 离线契约单测：把 numen 的 api / script / lua 三件制品只放进 test 运行时，
        // 用真实的 Binder + LuaCodecs 校验 @Fn 签名与返回值表能否绑定（不启动游戏）。
        testImplementation(files(numenApiJar))
        testImplementation(files(numenRoot.resolve("script/build/libs/numen-script-0.1.5.jar")))
        testImplementation(files(numenRoot.resolve("lua/build/libs/numen-lua-0.1.5.jar")))
    } else {
        logger.warn("[tfcie_prospecting] numen api jar 不存在，Lua API 注册层将编译失败: ${numenApiJar.path}")
        logger.warn("[tfcie_prospecting] 请先在 Numen 仓库执行 `./gradlew :numen-api:forge:build`，"
                + "再用 -PnumenRoot=<Numen 源码根> 或环境变量 NUMEN_ROOT 指向它。")
    }
}

minecraft {
    mappings("parchment", parchmentVersion)

    runs {
        register("client") {
            workingDirectory(project.file("run/client"))
            property("forge.logging.console.level", "debug")
            // 本模组的 mixin 按 TFC 自有名字注入（不生成 refmap）；开发期运行在官方名下，
            // 关掉 refmap 重映射，免得 Mixin 去找不存在的 refmap。
            property("mixin.env.disableRefMap", "true")
            // 开发期模组以源码集目录进运行，没有 jar MANIFEST 里的 MixinConfigs 清单项，
            // 只能显式把配置交给 Mixin；发行 jar 由下面 jar 任务的 MixinConfigs 清单属性接手。
            arg("--mixin.config")
            arg("tfcie_prospecting.mixins.json")
            mods.create(modId) {
                source(sourceSets.main.get())
            }
        }

        register("server") {
            workingDirectory(project.file("run/server"))
            arg("--nogui")
            arg("--mixin.config")
            arg("tfcie_prospecting.mixins.json")
            property("mixin.env.disableRefMap", "true")
            mods.create(modId) {
                source(sourceSets.main.get())
            }
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    jar {
        manifest {
            attributes["Implementation-Version"] = project.version
            // Forge 1.20.1 的 mixin 装载入口：Mixin 的 transformation service 读模组 jar 清单里的这一项。
            // 不在 mods.toml 里写 [[mixins]]（1.20.1 的 FML 没有这段解析；ImmersiveEngineering 等同代模组也都走清单）。
            attributes["MixinConfigs"] = "tfcie_prospecting.mixins.json"
        }
    }
}
