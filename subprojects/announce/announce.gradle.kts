import org.gradle.gradlebuild.unittestandcompile.ModuleType

/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    `java-library`
}

dependencies {
    implementation(project(":modelCore"))
    implementation(project(":baseServices"))
    implementation(project(":logging"))
    implementation(project(":processServices"))
    implementation(project(":coreApi"))
    implementation(project(":core"))

    implementation(library("slf4j_api"))
    implementation(library("commons_codec"))
    implementation(library("commons_io"))

    testRuntimeOnly(project(":plugins"))
    testRuntimeOnly(project(":runtimeApiInfo"))
    testImplementation(testFixtures(project(":core")))
}

gradlebuildJava {
    moduleType = ModuleType.CORE
}
