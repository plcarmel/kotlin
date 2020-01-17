/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ir.isMethodOfAny
import org.jetbrains.kotlin.backend.common.ir.passTypeArgumentsFrom
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.codegen.isJvmInterface
import org.jetbrains.kotlin.backend.jvm.ir.hasJvmDefault
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.deserialization.PLATFORM_DEPENDENT_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

internal val inheritedDefaultMethodsOnClassesPhase = makeIrFilePhase(
    ::InheritedDefaultMethodsOnClassesLowering,
    name = "InheritedDefaultMethodsOnClasses",
    description = "Add bridge-implementations in classes that inherit default implementations from interfaces"
)

private class InheritedDefaultMethodsOnClassesLowering(val context: JvmBackendContext) : IrElementVisitorVoid, ClassLoweringPass {

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun lower(declaration: IrClass) {
        super.visitClass(declaration)
        if (declaration.isJvmInterface) return

        generateInterfaceMethods(declaration)
    }

    private fun generateInterfaceMethods(irClass: IrClass) {
        irClass.declarations.transform { declaration ->
            when (declaration) {
                is IrSimpleFunction -> generateDelegationToDefaultImpl(declaration)
                is IrProperty -> declaration.also {
                    declaration.getter = declaration.getter?.let(this::generateDelegationToDefaultImpl) ?: declaration.getter
                    declaration.setter = declaration.setter?.let(this::generateDelegationToDefaultImpl) ?: declaration.setter
                }
                else -> null
            } ?: declaration
        }
    }

    // Functions introduced by this lowering may be inherited lower in the hierarchy.
    // Here we use the same logic as the delegation itself (`getTargetForRedirection`) to determine
    // if the overriden symbol has been, or will be, replaced and patch it accordingly.
    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        declaration.overriddenSymbols.replaceAll { symbol ->
            if (symbol.owner.getTargetForRedirection() != null)
                context.declarationFactory.getDefaultImplsRedirection(symbol.owner).symbol
            else symbol
        }
        super.visitSimpleFunction(declaration)
    }

    private fun IrSimpleFunction.getTargetForRedirection(): IrSimpleFunction? {
        if (origin != IrDeclarationOrigin.FAKE_OVERRIDE) return null
        parent.let { if (it is IrClass && it.isJvmInterface) return null }

        // Only generate interface delegation for functions immediately inherited from an interface.
        // (Otherwise, delegation will be present in the parent class)
        if (overriddenSymbols.any { !it.owner.parentAsClass.isInterface && it.owner.modality != Modality.ABSTRACT }) {
            return null
        }

        val implementation = resolveFakeOverride() ?: return null
        if (!implementation.hasInterfaceParent()
            || Visibilities.isPrivate(implementation.visibility)
            || implementation.isDefinitelyNotDefaultImplsMethod()
            || implementation.isMethodOfAny()
            || implementation.hasJvmDefault()
        ) {
            return null
        }

        return implementation
    }

    private fun generateDelegationToDefaultImpl(fakeOverride: IrSimpleFunction): IrSimpleFunction? {
        val interfaceImplementation = fakeOverride.getTargetForRedirection() ?: return null
        val irFunction = context.declarationFactory.getDefaultImplsRedirection(fakeOverride)

        val defaultImplFun = context.declarationFactory.getDefaultImplsFunction(interfaceImplementation)
        context.createIrBuilder(irFunction.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
            irFunction.body = irBlockBody {
                +irReturn(
                    irCall(defaultImplFun.symbol, irFunction.returnType).apply {
                        var offset = 0
                        passTypeArgumentsFrom(irFunction)
                        irFunction.dispatchReceiverParameter?.let { putValueArgument(offset++, irGet(it)) }
                        irFunction.extensionReceiverParameter?.let { putValueArgument(offset++, irGet(it)) }
                        irFunction.valueParameters.mapIndexed { i, parameter -> putValueArgument(i + offset, irGet(parameter)) }
                    }
                )
            }
        }

        return irFunction
    }
}

internal val interfaceSuperCallsPhase = makeIrFilePhase(
    lowering = ::InterfaceSuperCallsLowering,
    name = "InterfaceSuperCalls",
    description = "Redirect super interface calls to DefaultImpls"
)

private class InterfaceSuperCallsLowering(val context: JvmBackendContext) : IrElementTransformerVoid(), FileLoweringPass {

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        if (expression.superQualifierSymbol?.owner?.isInterface != true || expression.isSuperToAny()) {
            return super.visitCall(expression)
        }

        val superCallee = (expression.symbol.owner as IrSimpleFunction).resolveFakeOverride()!!
        if (superCallee.isDefinitelyNotDefaultImplsMethod() || superCallee.hasJvmDefault()) return super.visitCall(expression)

        val redirectTarget = context.declarationFactory.getDefaultImplsFunction(superCallee)
        val newCall = irCall(expression, redirectTarget, receiversAsArguments = true)

        return super.visitCall(newCall)
    }
}

internal val interfaceDefaultCallsPhase = makeIrFilePhase(
    lowering = ::InterfaceDefaultCallsLowering,
    name = "InterfaceDefaultCalls",
    description = "Redirect interface calls with default arguments to DefaultImpls"
)

private class InterfaceDefaultCallsLowering(val context: JvmBackendContext) : IrElementTransformerVoidWithContext(), FileLoweringPass {
    // TODO If there are no default _implementations_ we can avoid generating defaultImpls class entirely by moving default arg dispatchers to the interface class
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner

        if (!callee.hasInterfaceParent() ||
            callee.origin != IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER ||
            (callee.hasJvmDefault() && !context.state.jvmDefaultMode.isCompatibility)
        ) {
            return super.visitCall(expression)
        }

        val redirectTarget = context.declarationFactory.getDefaultImplsFunction(callee as IrSimpleFunction)

        // InterfaceLowering bridges from DefaultImpls in compatibility mode -- if that's the case,
        // this phase will inadvertently cause a recursive loop as the bridge on the DefaultImpls
        // gets redirected to call itself.
        if (redirectTarget == currentFunction?.irElement) return super.visitCall(expression)

        val newCall = irCall(expression, redirectTarget, receiversAsArguments = true)

        return super.visitCall(newCall)
    }
}

private fun IrSimpleFunction.isDefinitelyNotDefaultImplsMethod() =
    resolveFakeOverride()?.let { origin == IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB } == true ||
            origin == IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER ||
            hasAnnotation(PLATFORM_DEPENDENT_ANNOTATION_FQ_NAME) ||
            (name.asString() == "clone" &&
                    parent.safeAs<IrClass>()?.fqNameWhenAvailable?.asString() == "kotlin.Cloneable" &&
                    valueParameters.isEmpty())

internal val interfaceObjectCallsPhase = makeIrFilePhase(
    lowering = ::InterfaceObjectCallsLowering,
    name = "InterfaceObjectCalls",
    description = "Resolve calls to Object methods on interface types to virtual methods"
)

private class InterfaceObjectCallsLowering(val context: JvmBackendContext) : IrElementTransformerVoid(), FileLoweringPass {
    override fun lower(irFile: IrFile) = irFile.transformChildrenVoid(this)

    override fun visitCall(expression: IrCall): IrExpression {
        if (expression.superQualifierSymbol != null && !expression.isSuperToAny())
            return super.visitCall(expression)
        val callee = expression.symbol.owner
        if (callee !is IrSimpleFunction || !callee.hasInterfaceParent())
            return super.visitCall(expression)
        val resolved = callee.resolveFakeOverride()
        if (resolved?.isMethodOfAny() != true)
            return super.visitCall(expression)
        val newSuperQualifierSymbol = context.irBuiltIns.anyClass.takeIf { expression.superQualifierSymbol != null }
        return super.visitCall(irCall(expression, resolved, newSuperQualifierSymbol = newSuperQualifierSymbol))
    }
}
