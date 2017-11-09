/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin.reflect.jvm.internal

import kotlin.reflect.jvm.internal.impl.descriptors.*
import kotlin.reflect.jvm.internal.impl.incremental.components.NoLookupLocation
import kotlin.reflect.jvm.internal.impl.load.java.lazy.descriptors.LazyJavaPackageFragment
import kotlin.reflect.jvm.internal.impl.load.kotlin.KotlinJvmBinaryPackageSourceElement
import kotlin.reflect.jvm.internal.impl.name.Name
import kotlin.reflect.jvm.internal.impl.resolve.scopes.MemberScope
import kotlin.reflect.jvm.internal.impl.serialization.PackageData
import kotlin.reflect.jvm.internal.impl.serialization.deserialization.MemberDeserializer
import kotlin.reflect.jvm.internal.impl.serialization.deserialization.TypeTable
import kotlin.reflect.jvm.internal.impl.serialization.deserialization.descriptors.DeserializedCallableMemberDescriptor
import kotlin.reflect.jvm.internal.impl.serialization.jvm.JvmProtoBuf
import kotlin.reflect.jvm.internal.impl.serialization.jvm.JvmProtoBufUtil
import kotlin.reflect.KCallable
import kotlin.reflect.jvm.internal.KDeclarationContainerImpl.MemberBelonginess.DECLARED
import kotlin.reflect.jvm.internal.components.ReflectKotlinClass
import kotlin.reflect.jvm.internal.structure.classId

internal class KPackageImpl(
        override val jClass: Class<*>,
        @Suppress("unused") val usageModuleName: String? = null // may be useful for debug
) : KDeclarationContainerImpl() {
    private inner class Data : KDeclarationContainerImpl.Data() {
        private val kotlinClass: ReflectKotlinClass? by ReflectProperties.lazySoft {
            // TODO: do not read ReflectKotlinClass multiple times
            ReflectKotlinClass.create(jClass)
        }

        val descriptor: PackageViewDescriptor by ReflectProperties.lazySoft {
            with(moduleData) {
                kotlinClass?.packageModuleName?.let(packagePartProvider::registerModule)
                module.getPackage(jClass.classId.packageFqName)
            }
        }

        val methodOwner: Class<*> by ReflectProperties.lazy {
            val facadeName = kotlinClass?.classHeader?.multifileClassName
            // We need to check isNotEmpty because this is the value read from the annotation which cannot be null.
            // The default value for 'xs' is empty string, as declared in kotlin.Metadata
            if (facadeName != null && facadeName.isNotEmpty()) {
                jClass.classLoader.loadClass(facadeName.replace('/', '.'))
            }
            else {
                jClass
            }
        }

        val metadata: PackageData? by ReflectProperties.lazy {
            kotlinClass?.classHeader?.let { header ->
                val data = header.data
                val strings = header.strings
                if (data != null && strings != null) {
                    JvmProtoBufUtil.readPackageDataFrom(data, strings)
                }
                else null
            }
        }

        val members: Collection<KCallableImpl<*>> by ReflectProperties.lazySoft {
            getMembers(scope, DECLARED).filter { member ->
                val callableDescriptor = member.descriptor as DeserializedCallableMemberDescriptor
                val packageFragment = callableDescriptor.containingDeclaration as PackageFragmentDescriptor
                val source = (packageFragment as? LazyJavaPackageFragment)?.source as? KotlinJvmBinaryPackageSourceElement
                (source?.getContainingBinaryClass(callableDescriptor) as? ReflectKotlinClass)?.klass == jClass
            }
        }
    }

    private val data = ReflectProperties.lazy { Data() }

    override val methodOwner: Class<*> get() = data().methodOwner

    private val scope: MemberScope get() = data().descriptor.memberScope

    override val members: Collection<KCallable<*>> get() = data().members

    override val constructorDescriptors: Collection<ConstructorDescriptor>
        get() = emptyList()

    override fun getProperties(name: Name): Collection<PropertyDescriptor> =
            scope.getContributedVariables(name, NoLookupLocation.FROM_REFLECTION)

    override fun getFunctions(name: Name): Collection<FunctionDescriptor> =
            scope.getContributedFunctions(name, NoLookupLocation.FROM_REFLECTION)

    override fun getLocalProperty(index: Int): PropertyDescriptor? {
        return data().metadata?.let { (nameResolver, packageProto) ->
            val proto = packageProto.getExtension(JvmProtoBuf.packageLocalVariable, index)
            deserializeToDescriptor(jClass, proto, nameResolver, TypeTable(packageProto.typeTable), MemberDeserializer::loadProperty)
        }
    }

    override fun equals(other: Any?): Boolean =
            other is KPackageImpl && jClass == other.jClass

    override fun hashCode(): Int =
            jClass.hashCode()

    override fun toString(): String {
        val fqName = jClass.classId.packageFqName
        return "package " + (if (fqName.isRoot) "<default>" else fqName.asString())
    }
}
