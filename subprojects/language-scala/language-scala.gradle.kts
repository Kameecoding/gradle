import org.gradle.gradlebuild.unittestandcompile.ModuleType

plugins {
    `java-library`
    gradlebuild.`strict-compile`
}

dependencies {
    implementation(project(":baseServices"))
    implementation(project(":logging"))
    implementation(project(":processServices"))
    implementation(project(":workerProcesses"))
    implementation(project(":persistentCache"))
    implementation(project(":coreApi"))
    implementation(project(":modelCore"))
    implementation(project(":core"))
    implementation(project(":workers"))
    implementation(project(":platformBase"))
    implementation(project(":platformJvm"))
    implementation(project(":languageJava"))
    implementation(project(":languageJvm"))

    implementation(library("groovy")) // for 'Task.property(String propertyName) throws groovy.lang.MissingPropertyException'
    implementation(library("slf4j_api"))
    implementation(library("guava"))
    implementation(library("inject"))

    testImplementation(project(":fileCollections"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":platformBase")))
    testImplementation(testFixtures(project(":launcher")))
    testImplementation(testFixtures(project(":plugins")))

    testRuntimeOnly(project(":runtimeApiInfo"))

    integTestImplementation(library("commons_lang"))
    integTestImplementation(library("ant"))

    // keep in sync with ScalaLanguagePlugin code
    compileOnly("com.typesafe.zinc:zinc:0.3.15") {
        because("this dependency is downloaded by the antlr plugin; version should be in sync with ScalaLanguagePlugin code")
    }


    testFixturesApi(testFixtures(project(":languageJvm")))
    testFixturesImplementation(project(":baseServices"))
    testFixturesImplementation(project(":coreApi"))
    testFixturesImplementation(project(":modelCore"))
    testFixturesImplementation(project(":internalTesting"))
    testFixturesImplementation(project(":platformBase"))
    testFixturesImplementation(testFixtures(project(":languageJvm")))
}

gradlebuildJava {
    moduleType = ModuleType.CORE
}

