package dotty.tools.dotc
package core

import Periods._, Contexts._, Symbols._, Denotations._, Names._, NameOps._, Annotations._
import Types._, Flags._, Decorators._, Transformers._, StdNames._, Scopes._
import NameOps._
import Scopes.Scope
import collection.mutable
import collection.immutable.BitSet
import scala.reflect.io.AbstractFile
import Decorators.SymbolIteratorDecorator
import annotation.tailrec

trait SymDenotations { this: Context =>
  import SymDenotations._

  /** Factory method for SymDenotion creation. All creations
   *  should be done via this method.
   */
  def SymDenotation(
    symbol: Symbol,
    owner: Symbol,
    name: Name,
    initFlags: FlagSet,
    initInfo: Type,
    initPrivateWithin: Symbol = NoSymbol)(implicit ctx: Context): SymDenotation = {
    val result =
      if (symbol.isClass) new ClassDenotation(symbol, owner, name, initFlags, initInfo, initPrivateWithin)
      else new SymDenotation(symbol, owner, name, initFlags, initInfo, initPrivateWithin)
    result.validFor = stablePeriod
    result
  }

  def stillValid(denot: SymDenotation): Boolean =
    if (denot is ValidForever) true
    else if (denot.owner is PackageClass)
      (denot.owner.decls.lookup(denot.name) eq denot.symbol) ||
      (denot is ModuleClass) && stillValid(denot.sourceModule) // !!! DEBUG - we should check why module classes are not entered
    else stillValid(denot.owner)
}
object SymDenotations {

  /** A sym-denotation represents the contents of a definition
   *  during a period.
   */
  class SymDenotation private[SymDenotations] (
    final val symbol: Symbol,
    ownerIfExists: Symbol,
    final val name: Name,
    initFlags: FlagSet,
    initInfo: Type,
    initPrivateWithin: Symbol = NoSymbol) extends SingleDenotation {

    //assert(symbol.id != 4940, name)

    override def hasUniqueSym: Boolean = exists

    // ------ Getting and setting fields -----------------------------

    private[this] var myFlags: FlagSet = adaptFlags(initFlags)
    private[this] var myInfo: Type = initInfo
    private[this] var myPrivateWithin: Symbol = initPrivateWithin
    private[this] var myAnnotations: List[Annotation] = Nil

    /** The owner of the symbol */
    def owner: Symbol = ownerIfExists

    /** The flag set */
    final def flags: FlagSet = { ensureCompleted(); myFlags }

    final def flagsUNSAFE = myFlags // !!! DEBUG; drop when no longer needed

    /** Adapt flag set to this denotation's term or type nature */
    def adaptFlags(flags: FlagSet) = if (isType) flags.toTypeFlags else flags.toTermFlags

    /** Update the flag set */
    private final def flags_=(flags: FlagSet): Unit =
      myFlags = adaptFlags(flags)

    /** Set given flags(s) of this denotation */
    final def setFlag(flags: FlagSet): Unit = { myFlags |= flags }

    /** UnsSet given flags(s) of this denotation */
    final def resetFlag(flags: FlagSet): Unit = { myFlags &~= flags }

    final def is(fs: FlagSet) = {
      (if (fs <= FromStartFlags) myFlags else flags) is fs
    }
    final def is(fs: FlagSet, butNot: FlagSet) =
      (if (fs <= FromStartFlags && butNot <= FromStartFlags) myFlags else flags) is (fs, butNot)
    final def is(fs: FlagConjunction) =
      (if (fs <= FromStartFlags) myFlags else flags) is fs
    final def is(fs: FlagConjunction, butNot: FlagSet) =
      (if (fs <= FromStartFlags && butNot <= FromStartFlags) myFlags else flags) is (fs, butNot)

    /** The type info.
     *  The info is an instance of TypeType iff this is a type denotation
     *  Uncompleted denotations set myInfo to a LazyType.
     */
    final def info: Type = myInfo match {
      case myInfo: LazyType => completeFrom(myInfo); info
      case _ => myInfo
    }

    private def completeFrom(completer: LazyType): Unit = {
      if (myFlags is Touched) throw new CyclicReference(this)
      myFlags |= Touched

      Context.theBase.initialCtx.debugTraceIndented(s"completing ${this.debugString}") {
        try completer.complete(this) // !!! DEBUG
        catch {
          case ex: java.io.IOException =>
            println(s"error when completing $name#${symbol.id} ${completer.getClass}")
            throw ex
        }
      }
    }

    protected[dotc] final def info_=(tp: Type) = {
      def illegal: String = s"illegal type for $this: $tp"
      /*
      if (this is Module) // make sure module invariants that allow moduleClass and sourceModule to work are kept.
        tp match {
          case tp: ClassInfo => assert(tp.selfInfo.isInstanceOf[TermRefBySym], illegal)
          case tp: NamedType => assert(tp.isInstanceOf[TypeRefBySym], illegal)
          case tp: ExprType => assert(tp.resultType.isInstanceOf[TypeRefBySym], illegal)
          case _ =>
        }
        */
      myInfo = tp
    }

    /** The denotation is completed: all attributes are fully defined */
    final def isCompleted: Boolean = !myInfo.isInstanceOf[LazyType]

    final def isCompleting: Boolean = (myFlags is Touched) && !isCompleted

    /** The completer of this denotation. @pre: Denotation is not yet completed */
    final def completer: LazyType = myInfo.asInstanceOf[LazyType]

    /** Make sure this denotation is completed */
    final def ensureCompleted(): Unit = info

    /** The privateWithin boundary, NoSymbol if no boundary is given.
     */
    final def privateWithin: Symbol = { ensureCompleted(); myPrivateWithin }

    /** Set privateWithin. */
    protected[core] final def privateWithin_=(sym: Symbol): Unit =
      myPrivateWithin = sym

    /** The annotations of this denotation */
    final def annotations: List[Annotation] = {
      ensureCompleted(); myAnnotations
    }

    /** Update the annotations of this denotation */
    private[core] final def annotations_=(annots: List[Annotation]): Unit =
       myAnnotations = annots

    /** Does this denotation have an annotation matching the given class symbol? */
    final def hasAnnotation(cls: Symbol)(implicit ctx: Context) =
      dropOtherAnnotations(annotations, cls).nonEmpty

    /** Add given annotation to the annotations of this denotation */
    final def addAnnotation(annot: Annotation): Unit =
      annotations = annot :: myAnnotations

    @tailrec
    private def dropOtherAnnotations(anns: List[Annotation], cls: Symbol)(implicit ctx: Context): List[Annotation] = anns match {
      case ann :: rest => if (ann matches cls) anns else dropOtherAnnotations(rest, cls)
      case Nil => Nil
    }

    /** The symbols defined in this class.
     */
    final def decls(implicit ctx: Context): Scope = myInfo match {
      case cinfo: LazyType =>
        val knownDecls = cinfo.decls
        if (knownDecls ne EmptyScope) knownDecls
        else { completeFrom(cinfo); decls } // complete-once
      case _ => info.decls
    }

    /** If this is a package class, the symbols entered in it
     *  before it is completed. (this is needed to eagerly enter synthetic
     *  aliases such as AnyRef into a package class without forcing it.
     *  Right now, I believe the only usage is for the three synthetic aliases
     *  in Definitions.
     */
    final def preDecls(implicit ctx: Context): MutableScope = myInfo match {
      case pinfo: SymbolLoaders # PackageLoader => pinfo.preDecls
      case _ => decls.asInstanceOf[MutableScope]
    }

    // ------ Names ----------------------------------------------

    /** The name with which the denoting symbol was created */
    final def originalName = {
      val d = initial.asSymDenotation
      if (d is ExpandedName) d.name.unexpandedName() else d.name
    }

    /** The encoded full path name of this denotation, where outer names and inner names
     *  are separated by `separator` characters.
     *  Never translates expansions of operators back to operator symbol.
     *  Drops package objects. Represents terms in the owner chain by a simple `separator`.
     */
    def fullName(separator: Char)(implicit ctx: Context): Name =
      if (symbol == NoSymbol || owner == NoSymbol || owner.isEffectiveRoot) name
      else {
        var owner = this
        var sep = ""
        do {
          owner = owner.owner
          sep += separator
        } while (!owner.isClass)
        owner.skipPackageObject.fullName(separator) ++ sep ++ name
      }

    /** `fullName` where `.' is the separator character */
    def fullName(implicit ctx: Context): Name = fullName('.')

    // ----- Tests -------------------------------------------------

    /** Is this denotation a type? */
    override def isType: Boolean = name.isTypeName

    /** Is this denotation a class? */
    final def isClass: Boolean = symbol.isInstanceOf[ClassSymbol]

    /** Cast to class denotation */
    final def asClass: ClassDenotation = asInstanceOf[ClassDenotation]

    /** is this symbol the result of an erroneous definition? */
    def isError: Boolean = false

    /** Make denotation not exist */
    final def markAbsent(): Unit =
      myInfo = NoType

    /** Is symbol known to not exist? */
    final def isAbsent: Boolean =
      myInfo == NoType

    /** Is this symbol the root class or its companion object? */
    final def isRoot: Boolean = name.toTermName == nme.ROOT

    /** Is this symbol the empty package class or its companion object? */
    final def isEmptyPackage(implicit ctx: Context): Boolean =
      name.toTermName == nme.EMPTY_PACKAGE && owner.isRoot

    /** Is this symbol the empty package class or its companion object? */
    final def isEffectiveRoot(implicit ctx: Context) = isRoot || isEmptyPackage

    /** Is this symbol an anonymous class? */
    final def isAnonymousClass(implicit ctx: Context): Boolean =
      initial.asSymDenotation.name startsWith tpnme.ANON_CLASS

    /** Is this symbol a package object or its module class? */
    def isPackageObject(implicit ctx: Context): Boolean =
      (name.toTermName == nme.PACKAGEkw) && (owner is Package) && (this is Module)

    /** Is this symbol an abstract type? */
    final def isAbstractType = isType && (this is Deferred)

    /** Is this symbol an alias type? */
    final def isAliasType = isAbstractOrAliasType && !(this is Deferred)

    /** Is this symbol an abstract or alias type? */
    final def isAbstractOrAliasType = isType & !isClass

    /** Is this definition contained in `boundary`?
     *  Same as `ownersIterator contains boundary` but more efficient.
     */
    final def isContainedIn(boundary: Symbol)(implicit ctx: Context): Boolean = {
      def recur(sym: Symbol): Boolean =
        if (sym eq boundary) true
        else if (sym eq NoSymbol) false
        else if ((sym is PackageClass) && !(boundary is PackageClass)) false
        else recur(sym.owner)
      recur(symbol)
    }

    /** Is this denotation static (i.e. with no outer instance)? */
    final def isStatic(implicit ctx: Context) =
      (this is Static) || this.exists && owner.isStaticOwner

    /** Is this a package class or module class that defines static symbols? */
    final def isStaticOwner(implicit ctx: Context): Boolean =
      (this is PackageClass) || (this is ModuleClass) && isStatic

    /** Is this denotation defined in the same scope and compilation unit as that symbol? */
    final def isCoDefinedWith(that: Symbol)(implicit ctx: Context) =
      (this.effectiveOwner == that.effectiveOwner) &&
      (  !(this.effectiveOwner is PackageClass)
      || { val thisFile = this.symbol.associatedFile
           val thatFile = that.symbol.associatedFile
           (  thisFile == null
           || thatFile == null
           || thisFile.path == thatFile.path // Cheap possibly wrong check, then expensive normalization
           || thisFile.canonicalPath == thatFile.canonicalPath
           )
         }
      )

    /** Is this a denotation of a stable term (or an arbitrary type)? */
    final def isStable(implicit ctx: Context) = {
      val isUnstable =
        (this is UnstableValue) ||
        info.isVolatile && !hasAnnotation(defn.uncheckedStableClass)
      (this is Stable) || isType || {
        if (isUnstable) false
        else { setFlag(Stable); true }
      }
    }

    /** Is this a user defined "def" method? Excluded are accessors and stable values */
    final def isSourceMethod = this is (Method, butNot = Accessor)

    /** Is this a setter? */
    final def isGetter = (this is Accessor) && !originalName.isSetterName

    /** Is this a setter? */
    final def isSetter = (this is Accessor) && originalName.isSetterName

    /** is this the constructor of a class? */
    final def isClassConstructor = name == nme.CONSTRUCTOR

    /** Is this the constructor of a trait? */
    final def isTraitConstructor = name == nme.TRAIT_CONSTRUCTOR

    /** Is this the constructor of a trait or a class */
    final def isConstructor = name.isConstructorName

    /** Is this a local template dummmy? */
    final def isLocalDummy: Boolean = name.isLocalDummyName

    /** Does this symbol denote the primary constructor of its enclosing class? */
    final def isPrimaryConstructor(implicit ctx: Context) =
      isConstructor && owner.primaryConstructor == this

    /** Is this a subclass of the given class `base`? */
    def isSubClass(base: Symbol)(implicit ctx: Context) = false

    /** Is this a subclass of `base`,
     *  and is the denoting symbol also different from `Null` or `Nothing`?
     */
    def derivesFrom(base: Symbol)(implicit ctx: Context) = false

    /** Is this symbol a class that does not extend `AnyVal`? */
    final def isNonValueClass(implicit ctx: Context): Boolean =
      isClass && !isSubClass(defn.AnyValClass)

    /** Is this definition accessible as a member of tree with type `pre`?
     *  @param pre          The type of the tree from which the selection is made
     *  @param superAccess  Access is via super
     *  Everything is accessible if `pre` is `NoPrefix`.
     *  A symbol with type `NoType` is not accessible for any other prefix.
     */
    final def isAccessibleFrom(pre: Type, superAccess: Boolean = false, whyNot: StringBuffer = null)(implicit ctx: Context): Boolean = {

      /** Are we inside definition of `boundary`? */
      def accessWithin(boundary: Symbol) =
        owner.isContainedIn(boundary) &&
          (!(this is JavaDefined) || // disregard package nesting for Java
           owner.enclosingPackage == boundary.enclosingPackage)

      /** Are we within definition of linked class of `boundary`? */
      def accessWithinLinked(boundary: Symbol) = {
        val linked = boundary.linkedClass
        (linked ne NoSymbol) && accessWithin(linked)
      }

      /** Is `pre` of the form C.this, where C is exactly the owner of this symbol,
       *  or, if this symbol is protected, a subclass of the owner?
       */
      def isCorrectThisType(pre: Type): Boolean = pre match {
        case ThisType(pclazz) =>
          (pclazz eq owner) ||
            (this is Protected) && pclazz.derivesFrom(owner)
        case _ => false
      }

      /** Is protected access to target symbol permitted? */
      def isProtectedAccessOK = {
        def fail(str: => String): Boolean = {
          if (whyNot != null) whyNot append str
          false
        }
        val cls = owner.enclosingSubClass
        if (!cls.exists)
          fail(
            s""" Access to protected $this not permitted because
               | enclosing ${ctx.owner.enclosingClass.showLocated} is not a subclass of
               | ${owner.showLocated} where target is defined""".stripMargin)
        else if (
          !(  isType // allow accesses to types from arbitrary subclasses fixes #4737
           || pre.baseType(cls).exists
           || (owner is ModuleClass) // don't perform this check for static members
           ))
          fail(
            s""" Access to protected ${symbol.show} not permitted because
               | prefix type ${pre.widen.show} does not conform to
               | ${cls.showLocated} where the access takes place""".stripMargin)
        else true
      }

      if (pre eq NoPrefix) true
      else if (info eq NoType) false
      else {
        val boundary = accessBoundary(owner)

        (  boundary.isTerm
        || boundary.isRoot
        || (accessWithin(boundary) || accessWithinLinked(boundary)) &&
             (  !(this is Local)
             || (owner is ImplClass) // allow private local accesses to impl class members
             || isCorrectThisType(pre)
             )
        || (this is Protected) &&
             (  superAccess
             || pre.isInstanceOf[ThisType]
             || ctx.phase.erasedTypes
             || isProtectedAccessOK
             )
        )
      }
    }

    /** Do members of this symbol need translation via asSeenFrom when
     *  accessed via prefix `pre`?
     */
    def membersNeedAsSeenFrom(pre: Type)(implicit ctx: Context) =
      !(  this.isTerm
       || this.isStaticOwner
       || ctx.erasedTypes && symbol != defn.ArrayClass
       || (pre eq thisType)
       )

    /** Is this symbol concrete, or that symbol deferred? */
    def isAsConcrete(that: Symbol)(implicit ctx: Context): Boolean =
      !(this is Deferred) || (that is Deferred)

    /** Does this symbol have defined or inherited default parameters? */
    def hasDefaultParams(implicit ctx: Context): Boolean =
      (this is HasDefaultParams) ||
        !(this is NoDefaultParams) && computeDefaultParams

    private def computeDefaultParams(implicit ctx: Context) = {
      val result = allOverriddenSymbols exists (_.hasDefaultParams)
      setFlag(if (result) InheritedDefaultParams else NoDefaultParams)
      result
    }

    //    def isOverridable: Boolean = !!! need to enforce that classes cannot be redefined
    //    def isSkolem: Boolean = ???

    // ------ access to related symbols ---------------------------------

    /* Modules and module classes are represented as follows:
     *
     * object X extends Y { def f() }
     *
     * <module> lazy val X: X$ = new X$
     * <module> class X$ extends Y { this: X.type => def f() }
     *
     * During completion, references to moduleClass and sourceModules are stored in
     * the completers.
     */
    /** The class implementing this module, NoSymbol if not applicable. */
    final def moduleClass(implicit ctx: Context): Symbol =
      if (this is ModuleVal)
        myInfo match {
          case info: TypeRef           => info.symbol
          case ExprType(info: TypeRef) => info.symbol // needed after uncurry, when module terms might be accessor defs
          case info: LazyType          => info.moduleClass
          case _                       => println(s"missing module class for $name: $myInfo"); NoSymbol
        }
      else NoSymbol

    /** The module implemented by this module class, NoSymbol if not applicable. */
    final def sourceModule(implicit ctx: Context): Symbol = myInfo match {
      case ClassInfo(_, _, _, _, selfType: TermRef) if this is ModuleClass =>
        selfType.symbol
      case info: LazyType =>
        info.sourceModule
      case _ =>
        NoSymbol
    }

    /** The chain of owners of this denotation, starting with the denoting symbol itself */
    final def ownersIterator(implicit ctx: Context) = new Iterator[Symbol] {
      private[this] var current = symbol
      def hasNext = current.exists
      def next: Symbol = {
        val result = current
        current = current.owner
        result
      }
    }

    /** If this is a package object or its implementing class, its owner,
     *  otherwise the denoting symbol.
     */
    final def skipPackageObject(implicit ctx: Context): Symbol =
      if (isPackageObject) owner else symbol

    /** The owner, skipping package objects. */
    final def effectiveOwner(implicit ctx: Context) = owner.skipPackageObject

    /** The class containing this denotation.
     *  If this denotation is already a class, return itself
     */
    final def enclosingClass(implicit ctx: Context): Symbol =
      if (isClass) symbol else owner.enclosingClass

    final def enclosingClassNamed(name: Name)(implicit ctx: Context): Symbol = {
      val cls = enclosingClass
      if (cls.name == name) cls else cls.owner.enclosingClassNamed(name)
    }

    /** The top-level class containing this denotation,
     *  except for a toplevel module, where its module class is returned.
     */
    final def topLevelClass(implicit ctx: Context): Symbol = {
      val sym = topLevelSym
      if (sym.isClass) sym else sym.moduleClass
    }

    /** The top-level symbol containing this denotation. */
    final def topLevelSym(implicit ctx: Context): Symbol =
      if ((this is PackageClass) || (owner is PackageClass)) symbol
      else owner.topLevelSym

    /** The package containing this denotation */
    final def enclosingPackage(implicit ctx: Context): Symbol =
      if (this is PackageClass) symbol else owner.enclosingPackage

    /** The module object with the same (term-) name as this class or module class,
     *  and which is also defined in the same scope and compilation unit.
     *  NoSymbol if this module does not exist.
     */
    final def companionModule(implicit ctx: Context): Symbol =
      if (owner.exists)
        owner.info.decl(name.stripModuleClassSuffix.toTermName)
          .suchThat(sym => (sym is Module) && sym.isCoDefinedWith(symbol))
          .symbol
      else NoSymbol

    /** The class with the same (type-) name as this module or module class,
     *  and which is also defined in the same scope and compilation unit.
     *  NoSymbol if this class does not exist.
     */
    final def companionClass(implicit ctx: Context): Symbol =
      if (owner.exists)
        owner.info.decl(name.stripModuleClassSuffix.toTypeName)
          .suchThat(sym => sym.isClass && sym.isCoDefinedWith(symbol))
          .symbol
      else NoSymbol

    /** If this is a class, the module class of its companion object.
     *  If this is a module class, its companion class.
     *  NoSymbol otherwise.
     */
    final def linkedClass(implicit ctx: Context): Symbol =
      if (this is ModuleClass) companionClass
      else if (this.isClass) companionModule.moduleClass
      else NoSymbol

    /** The class that encloses the owner of the current context
     *  and that is a subclass of this class. NoSymbol if no such class exists.
     */
    final def enclosingSubClass(implicit ctx: Context) =
      ctx.owner.ownersIterator.findSymbol(_.isSubClass(symbol))

    /** The non-private symbol whose name and type matches the type of this symbol
     *  in the given class.
     *  @param inClass   The class containing the symbol's definition
     *  @param site      The base type from which member types are computed
     *
     *  inClass <-- find denot.symbol      class C { <-- symbol is here
     *
     *                   site: Subtype of both inClass and C
     */
    final def matchingSymbol(inClass: Symbol, site: Type)(implicit ctx: Context): Symbol = {
      var denot = inClass.info.nonPrivateDecl(name)
      if (denot.isTerm) // types of the same name always match
        denot = denot.matchingDenotation(site, site.memberInfo(symbol))
      denot.symbol
    }

    /** The symbol, in class `inClass`, that is overridden by this denotation. */
    final def overriddenSymbol(inClass: ClassSymbol)(implicit ctx: Context): Symbol =
      matchingSymbol(inClass, owner.thisType)

    /** All symbols overriden by this denotation. */
    final def allOverriddenSymbols(implicit ctx: Context): Iterator[Symbol] =
      owner.info.baseClasses.tail.iterator map overriddenSymbol filter (_.exists)

    /** The class or term symbol up to which this symbol is accessible,
     *  or RootClass if it is public.  As java protected statics are
     *  otherwise completely inaccessible in scala, they are treated
     *  as public.
     *  @param base  The access boundary to assume if this symbol is protected
     */
    final def accessBoundary(base: Symbol)(implicit ctx: Context): Symbol = {
      val fs = flags
      if (fs is PrivateOrLocal) owner
      else if (fs is StaticProtected) defn.RootClass
      else if (privateWithin.exists && !ctx.phase.erasedTypes) privateWithin
      else if (fs is Protected) base
      else defn.RootClass
    }

    /** The primary constructor of a class or trait, NoSymbol if not applicable. */
    def primaryConstructor(implicit ctx: Context): Symbol = NoSymbol

    // ----- type-related ------------------------------------------------

    /** The type parameters of a class symbol, Nil for all other symbols */
    def typeParams(implicit ctx: Context): List[TypeSymbol] = Nil

    /** The type This(cls), where cls is this class, NoPrefix for all other symbols */
    def thisType(implicit ctx: Context): Type = NoPrefix

    /** The named typeref representing the type constructor for this type.
     *  @throws ClassCastException is this is not a type
     */
    def typeConstructor(implicit ctx: Context): TypeRef =
      if ((this is PackageClass) || owner.isTerm) symTypeRef
      else TypeRef(owner.thisType, name.asTypeName).withDenot(this)

    /** The symbolic typeref representing the type constructor for this type.
     *  @throws ClassCastException is this is not a type
     */
    final def symTypeRef(implicit ctx: Context): TypeRef =
      TypeRef.withSym(owner.thisType, symbol.asType)

    /** The symbolic termref pointing to this termsymbol
     *  @throws ClassCastException is this is not a term
     */
    def symTermRef(implicit ctx: Context): TermRef =
      TermRef.withSym(owner.thisType, symbol.asTerm)

    def symRef(implicit ctx: Context): NamedType =
      NamedType.withSym(owner.thisType, symbol)

    /** The variance of this type parameter or type member as an Int, with
     *  +1 = Covariant, -1 = Contravariant, 0 = Nonvariant, or not a type parameter
     */
    final def variance: Int =
      if (this is Covariant) 1
      else if (this is Contravariant) -1
      else 0

    /** If this is a privatye[this] or protected[this] type parameter or type member,
     *  its variance, otherwise 0.
     */
    final def localVariance: Int =
      if (this is LocalCovariant) 1
      else if (this is LocalContravariant) -1
      else 0

    override def toString = {
      val kindString =
        if (this is ModuleClass) "module class"
        else if (isClass) "class"
        else if (isType) "type"
        else if (this is Module) "module"
        else "val"
      s"$kindString $name"
    }

    val debugString = toString+"#"+symbol.id // !!! DEBUG

    // ----- copies ------------------------------------------------------

    override protected def newLikeThis(s: Symbol, i: Type): SingleDenotation = new UniqueRefDenotation(s, i, validFor)

    /** Copy this denotation, overriding selective fields */
    final def copySymDenotation(
      symbol: Symbol = this.symbol,
      owner: Symbol = this.owner,
      name: Name = this.name,
      initFlags: FlagSet = this.flags,
      info: Type = this.info,
      privateWithin: Symbol = this.privateWithin,
      annotations: List[Annotation] = this.annotations)(implicit ctx: Context) =
    {
      val d = ctx.SymDenotation(symbol, owner, name, initFlags, info, privateWithin)
      d.annotations = annotations
      d
    }
  }

  /** The contents of a class definition during a period
   */
  class ClassDenotation private[SymDenotations] (
    symbol: Symbol,
    ownerIfExists: Symbol,
    name: Name,
    initFlags: FlagSet,
    initInfo: Type,
    initPrivateWithin: Symbol = NoSymbol)
    extends SymDenotation(symbol, ownerIfExists, name, initFlags, initInfo, initPrivateWithin) {

    import util.LRUCache

    // ----- denotation fields and accessors ------------------------------

    if (initFlags is (Module, butNot = Package)) assert(name.isModuleClassName, this)

    /** The symbol asserted to have type ClassSymbol */
    def classSymbol: ClassSymbol = symbol.asInstanceOf[ClassSymbol]

    /** The info asserted to have type ClassInfo */
    def classInfo(implicit ctx: Context): ClassInfo = super.info.asInstanceOf[ClassInfo]

    /** TODO: Document why caches are supposedly safe to use */
    private[this] var myTypeParams: List[TypeSymbol] = _

    /** The type parameters of this class */
    override final def typeParams(implicit ctx: Context): List[TypeSymbol] = {
      if (myTypeParams == null) myTypeParams = computeTypeParams
      myTypeParams
    }

    private def computeTypeParams(implicit ctx: Context): List[TypeSymbol] =
      decls.filter(sym =>
        (sym is TypeParam) && sym.owner == symbol).asInstanceOf[List[TypeSymbol]]

    /** A key to verify that all caches influenced by parent classes are valid */
    private var parentDenots: List[Denotation] = null

    /** The denotations of all parents in this class.
     *  Note: Always use this method instead of `classInfo.classParents`
     *  because the latter does not ensure that the `parentDenots` key
     *  is up-to-date, which might lead to invalid caches later on.
     */
    def classParents(implicit ctx: Context) = {
      val ps = classInfo.classParents
      if (parentDenots == null) parentDenots = ps map (_.denot)
      ps
    }

    /** Are caches influenced by parent classes still valid? */
    private def parentsAreValid(implicit ctx: Context): Boolean =
      parentDenots == null ||
      parentDenots.corresponds(classInfo.classParents)(_ eq _.denot)

    /** If caches influenced by parent classes are still valid, the denotation
     *  itself, otherwise a freshly initialized copy.
     */
    override def copyIfParentInvalid(implicit ctx: Context): SingleDenotation =
      if (!parentsAreValid) copySymDenotation() else this

   // ------ class-specific operations -----------------------------------

    private[this] var myThisType: Type = null

    override def thisType(implicit ctx: Context): Type = {
      if (myThisType == null) myThisType = computeThisType
      myThisType
    }

    private def computeThisType(implicit ctx: Context): Type = ThisType(classSymbol) /*
      if ((this is PackageClass) && !isRoot)
        TermRef(owner.thisType, name.toTermName)
      else
        ThisType(classSymbol) */

    private[this] var myTypeConstructor: TypeRef = null

    override def typeConstructor(implicit ctx: Context): TypeRef = {
      if (myTypeConstructor == null) myTypeConstructor = super.typeConstructor
      myTypeConstructor
    }

    private[this] var myBaseClasses: List[ClassSymbol] = null
    private[this] var mySuperClassBits: BitSet = null

    private def computeBases(implicit ctx: Context): Unit = {
      if (myBaseClasses == Nil) throw new CyclicReference(this)
      myBaseClasses = Nil
      val seen = new mutable.BitSet
      val locked = new mutable.BitSet
      def addBaseClasses(bcs: List[ClassSymbol], to: List[ClassSymbol])
          : List[ClassSymbol] = bcs match {
        case bc :: bcs1 =>
          val bcs1added = addBaseClasses(bcs1, to)
          val id = bc.superId
          if (seen contains id) bcs1added
          else {
            seen += id
            bc :: bcs1added
          }
        case nil =>
          to
      }
      def addParentBaseClasses(ps: List[Type], to: List[ClassSymbol]): List[ClassSymbol] = ps match {
        case p :: ps1 =>
          addParentBaseClasses(ps1, addBaseClasses(p.baseClasses, to))
        case nil =>
          to
      }
      myBaseClasses = classSymbol :: addParentBaseClasses(classParents, Nil)
      mySuperClassBits = ctx.uniqueBits.findEntryOrUpdate(seen.toImmutable)
    }

    /** A bitset that contains the superId's of all base classes */
    private def superClassBits(implicit ctx: Context): BitSet = {
      if (mySuperClassBits == null) computeBases
      mySuperClassBits
    }

    /** The base classes of this class in linearization order,
     *  with the class itself as first element.
     */
    def baseClasses(implicit ctx: Context): List[ClassSymbol] = {
      if (myBaseClasses == null) computeBases
      myBaseClasses
    }

    final override def derivesFrom(base: Symbol)(implicit ctx: Context): Boolean =
      base.isClass &&
      (  (symbol eq base)
      || (superClassBits contains base.superId)
      || (this is Erroneous)
      || (base is Erroneous)
      )

    final override def isSubClass(base: Symbol)(implicit ctx: Context) =
      derivesFrom(base) ||
        base.isClass && (
          (symbol eq defn.NothingClass) ||
            (symbol eq defn.NullClass) && (base ne defn.NothingClass))

    private[this] var myMemberFingerPrint: FingerPrint = FingerPrint.unknown

    private def computeMemberFingerPrint(implicit ctx: Context): FingerPrint = {
      var fp = FingerPrint()
      var e = info.decls.lastEntry
      while (e != null) {
        fp.include(e.sym.name)
        e = e.prev
      }
      var ps = classParents
      while (ps.nonEmpty) {
        val parent = ps.head.typeSymbol
        parent.denot match {
          case classd: ClassDenotation =>
            fp.include(classd.memberFingerPrint)
            parent.denot.setFlag(Frozen)
          case _ =>
        }
        ps = ps.tail
      }
      fp
    }

    /** A bloom filter for the names of all members in this class.
     *  Makes sense only for parent classes, and should definitely
     *  not be used for package classes because cache never
     *  gets invalidated.
     */
    def memberFingerPrint(implicit ctx: Context): FingerPrint = {
      if (myMemberFingerPrint == FingerPrint.unknown) myMemberFingerPrint = computeMemberFingerPrint
      myMemberFingerPrint
    }

    private[this] var myMemberCache: LRUCache[Name, PreDenotation] = null

    private def memberCache: LRUCache[Name, PreDenotation] = {
      if (myMemberCache == null) myMemberCache = new LRUCache
      myMemberCache
    }

    /** Enter a symbol in current scope.
     *  Note: We require that this does not happen after the first time
     *  someone does a findMember on a subclass.
     */
    def enter(sym: Symbol, scope: Scope = EmptyScope)(implicit ctx: Context) = {
      require(!(this is Frozen))
      val mscope = scope match {
        case scope: MutableScope => scope
        case _ => decls.asInstanceOf[MutableScope]
      }
      if (this is PackageClass) { // replace existing symbols
        val entry = mscope.lookupEntry(sym.name)
        if (entry != null) mscope.unlink(entry)
      }
      mscope.enter(sym)

      if (myMemberFingerPrint != FingerPrint.unknown)
        memberFingerPrint.include(sym.name)
      if (myMemberCache != null)
        memberCache invalidate sym.name
    }

    /** Delete symbol from current scope.
     *  Note: We require that this does not happen after the first time
     *  someone does a findMember on a subclass.
     */
    def delete(sym: Symbol)(implicit ctx: Context) = {
      require(!(this is Frozen))
      info.decls.asInstanceOf[MutableScope].unlink(sym)
      if (myMemberFingerPrint != FingerPrint.unknown)
        computeMemberFingerPrint
      if (myMemberCache != null)
        memberCache invalidate sym.name
    }

    /** All members of this class that have the given name.
     *  The elements of the returned pre-denotation all
     *  have existing symbols.
     */
    final def membersNamed(name: Name)(implicit ctx: Context): PreDenotation = {
      var denots: PreDenotation = memberCache lookup name
      if (denots == null) {
        denots = computeMembersNamed(name)
        memberCache enter (name, denots)
      }
      denots
    }

    private def computeMembersNamed(name: Name)(implicit ctx: Context): PreDenotation =
      if (!classSymbol.hasChildren || (memberFingerPrint contains name)) {
        val ownDenots = decls.denotsNamed(name)
        if (debugTrace)  // DEBUG
          println(s"$this.member($name), ownDenots = $ownDenots")
        def collect(denots: PreDenotation, parents: List[TypeRef]): PreDenotation = parents match {
          case p :: ps =>
            val denots1 = collect(denots, ps)
            p.symbol.denot match {
              case parentd: ClassDenotation =>
                if (debugTrace) {  // DEBUG
                  val s1 = parentd.membersNamed(name)
                  val s2 = s1.filterExcluded(Private)
                  val s3 = s2.disjointAsSeenFrom(ownDenots, thisType)
                  println(s"$this.member($name) $s1 $s2 $s3")
                }
                denots1 union
                  parentd.membersNamed(name)
                    .dropUniqueRefsIn(denots1)
                    .filterExcluded(Private)
                    .disjointAsSeenFrom(ownDenots, thisType)
              case _ =>
                denots1
            }
          case nil =>
            denots
        }
        if (name.isConstructorName) ownDenots
        else collect(ownDenots, classParents)
      } else NoDenotation

    override final def findMember(name: Name, pre: Type, excluded: FlagSet)(implicit ctx: Context): Denotation =
      membersNamed(name).filterExcluded(excluded).asSeenFrom(pre).toDenot(pre)

    private[this] var baseTypeCache: java.util.HashMap[CachedType, Type] = null
    private[this] var baseTypeValid: RunId = NoRunId

    /** Compute tp.baseType(this) */
    final def baseTypeOf(tp: Type)(implicit ctx: Context): Type = {

      def foldGlb(bt: Type, ps: List[Type]): Type = ps match {
        case p :: ps1 => foldGlb(bt & baseTypeOf(p), ps1)
        case _ => bt
      }

      def computeBaseTypeOf(tp: Type): Type = tp match {
        case tp: TypeRef =>
          val subcls = tp.symbol
          if (subcls eq symbol)
            tp
          else subcls.denot match {
            case cdenot: ClassDenotation =>
              if (cdenot.superClassBits contains symbol.superId) foldGlb(NoType, tp.parents)
              else NoType
            case _ =>
              baseTypeOf(tp.underlying)
          }
        case tp: TypeProxy =>
          baseTypeOf(tp.underlying)
        case AndType(tp1, tp2) =>
          baseTypeOf(tp1) & baseTypeOf(tp2)
        case OrType(tp1, tp2) =>
          baseTypeOf(tp1) | baseTypeOf(tp2)
        case _ =>
          NoType
      }

      ctx.debugTraceIndented(s"$tp.baseType($this)") {
        if (symbol.isStatic && tp.derivesFrom(symbol))
          symbol.typeConstructor
        else tp match {
          case tp: CachedType =>
            if (baseTypeValid != ctx.runId) {
              baseTypeCache = new java.util.HashMap[CachedType, Type]
              baseTypeValid = ctx.runId
            }
            var basetp = baseTypeCache get tp
            if (basetp == null) {
              baseTypeCache.put(tp, NoPrefix)
              basetp = computeBaseTypeOf(tp)
              baseTypeCache.put(tp, basetp)
            } else if (basetp == NoPrefix) {
              throw new CyclicReference(this)
            }
            basetp
          case _ =>
            computeBaseTypeOf(tp)
        }
      }
    }

    private[this] var memberNamesCache: Map[NameFilter, Set[Name]] = Map()

    def memberNames(keepOnly: NameFilter)(implicit ctx: Context): Set[Name] = {
      def computeMemberNames: Set[Name] = {
        val inheritedNames = (classParents flatMap (_.memberNames(keepOnly, thisType))).toSet
        val ownNames = info.decls.iterator map (_.name)
        val candidates = inheritedNames ++ ownNames
        candidates filter (keepOnly(thisType, _))
      }
      if (this is PackageClass) computeMemberNames // don't cache package member names; they might change
      else memberNamesCache get keepOnly match {
        case Some(names) =>
          names
        case _ =>
          setFlag(Frozen)
          val names = computeMemberNames
          memberNamesCache = memberNamesCache.updated(keepOnly, names)
          names
      }
    }

    private[this] var fullNameCache: Map[Char, Name] = Map()

    override final def fullName(separator: Char)(implicit ctx: Context): Name =
      fullNameCache get separator match {
        case Some(fn) =>
          fn
        case _ =>
          val fn = super.fullName(separator)
          fullNameCache = fullNameCache.updated(separator, fn)
          fn
      }

    // to avoid overloading ambiguities
    override def fullName(implicit ctx: Context): Name = super.fullName

    override def primaryConstructor(implicit ctx: Context): Symbol = {
      val cname =
        if (this is Trait | ImplClass) nme.TRAIT_CONSTRUCTOR else nme.CONSTRUCTOR
      decls.denotsNamed(cname).first.symbol
    }
  }

  object NoDenotation extends SymDenotation(
    NoSymbol, NoSymbol, "<none>".toTermName, Permanent, NoType) {
    override def exists = false
    override def isTerm = false
    override def isType = false
    override def owner: Symbol = throw new AssertionError("NoDenotation.owner")
    override def asSeenFrom(pre: Type)(implicit ctx: Context): SingleDenotation = this
    validFor = Period.allInRun(NoRunId) // will be brought forward automatically
  }

  // ---- Completion --------------------------------------------------------

  /** Instances of LazyType are carried by uncompleted symbols.
   *  Note: LazyTypes double up as (constant) functions from Symbol and
   *  from (TermSymbol, ClassSymbol) to LazyType. That way lazy types can be
   *  directly passed to symbol creation methods in Symbols that demand instances
   *  of these types.
   */
  abstract class LazyType extends UncachedGroundType
    with (Symbol => LazyType)
    with ((TermSymbol, ClassSymbol) => LazyType) { self =>

    /** Sets all missing fields of given denotation */
    def complete(denot: SymDenotation): Unit

    def apply(sym: Symbol) = this
    def apply(module: TermSymbol, modcls: ClassSymbol) = this

    private var myDecls: Scope = EmptyScope
    private var mySourceModuleFn: () => Symbol = NoSymbolFn
    private var myModuleClassFn: () => Symbol = NoSymbolFn

    def proxy: LazyType = new LazyType {
      override def complete(denot: SymDenotation) = self.complete(denot)
    }

    def decls: Scope = myDecls
    def sourceModule: Symbol = mySourceModuleFn()
    def moduleClass: Symbol = myModuleClassFn()

    def withDecls(decls: Scope): this.type = { myDecls = decls; this }
    def withSourceModule(sourceModule: => Symbol): this.type = { mySourceModuleFn = () => sourceModule; this }
    def withModuleClass(moduleClass: => Symbol): this.type = { myModuleClassFn = () => moduleClass; this }
  }

  val NoSymbolFn = () => NoSymbol

  class NoCompleter extends LazyType {
    def complete(denot: SymDenotation): Unit = unsupported("complete")
  }

  /** A missing completer */
  object NoCompleter extends LazyType {
    override def complete(denot: SymDenotation): Unit = unsupported("complete")
  }

  /** A lazy type for modules that points to the module class.
   *  Needed so that `moduleClass` works before completion.
   *  Completion of modules is always completion of the underlying
   *  module class, followed by copying the relevant fields to the module.
   */
  class ModuleCompleter(override val moduleClass: ClassSymbol)(implicit cctx: CondensedContext)
  extends LazyType {
    def complete(denot: SymDenotation): Unit = {
      val from = denot.moduleClass.denot.asClass
      denot.setFlag(from.flags.toTermFlags & RetainedModuleValFlags)
      denot.annotations = from.annotations filter (_.appliesToModule)
        // !!! ^^^ needs to be revised later. The problem is that annotations might
        // only apply to the module but not to the module class. The right solution
        // is to have the module class completer set the annotations of both the
        // class and the module.
      denot.info = moduleClass.symTypeRef
      denot.privateWithin = from.privateWithin
    }
  }

  /** A completer for missing references */
  class StubInfo()(implicit cctx: CondensedContext) extends LazyType {

    def initializeToDefaults(denot: SymDenotation) = {
      denot.info = denot match {
        case denot: ClassDenotation =>
          ClassInfo(denot.owner.thisType, denot.classSymbol, Nil, EmptyScope)
        case _ =>
          ErrorType
      }
      denot.privateWithin = NoSymbol
    }

    def complete(denot: SymDenotation): Unit = {
      val sym = denot.symbol
      val file = sym.associatedFile
      val (location, src) =
        if (file != null) (s" in $file", file.toString)
        else ("", "the signature")
      val name = cctx.fresh.withSetting(cctx.settings.debugNames, true).nameString(denot.name)
      cctx.error(
        s"""|bad symbolic reference. A signature$location
            |refers to $name in ${denot.owner.showKind} ${denot.owner.showFullName} which is not available.
            |It may be completely missing from the current classpath, or the version on
            |the classpath might be incompatible with the version used when compiling $src.""".stripMargin)
      if (cctx.debug) throw new Error()
      initializeToDefaults(denot)
    }
  }

  // ---- Fingerprints -----------------------------------------------------

  /** A fingerprint is a bitset that acts as a bloom filter for sets
   *  of names.
   */
  class FingerPrint(val bits: Array[Long]) extends AnyVal {
    import FingerPrint._

    /** Include some bits of name's hashcode in set */
    def include(name: Name): Unit = {
      val hash = name.hashCode & Mask
      bits(hash >> WordSizeLog) |= (1L << hash)
    }

    /** Include all bits of `that` fingerprint in set */
    def include(that: FingerPrint): Unit =
      for (i <- 0 until NumWords) bits(i) |= that.bits(i)

    /** Does set contain hash bits of given name? */
    def contains(name: Name): Boolean = {
      val hash = name.hashCode & Mask
      (bits(hash >> WordSizeLog) & (1L << hash)) != 0
    }
  }

  object FingerPrint {
    def apply() = new FingerPrint(new Array[Long](NumWords))
    val unknown = new FingerPrint(null)
    private final val WordSizeLog = 6
    private final val NumWords = 32
    private final val NumBits = NumWords << WordSizeLog
    private final val Mask = NumBits - 1
  }
}
