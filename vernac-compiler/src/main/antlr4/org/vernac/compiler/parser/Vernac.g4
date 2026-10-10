grammar Vernac;

compilationUnit
    : namespaceDeclaration importDeclaration* topLevelDeclaration* EOF
    ;

namespaceDeclaration
    : 'namespace' qualifiedName ';'
    ;

importDeclaration
    : 'import' qualifiedName ('.' '*')? ';'
    ;

topLevelDeclaration
    : idDeclaration
    | valueDefinition
    | eventDefinition
    | aggregateDefinition
    | entityDefinition
    | portDefinition
    | repositoryDefinition
    | usecaseDefinition
    | domainServiceDefinition
    | listenerDefinition
    ;

// ==========================================
// 0. Identifier Types
// ==========================================
idDeclaration
    : 'id' name=typeName collectionDefinition? ';'?
    ;

// ==========================================
// 1. Value Objects & Enums
// ==========================================
valueDefinition
    : 'value' name=typeName (
        '(' parameterList? ')' ( 'validates' validationBlock )?
      | '=' enumConstantList
      )
      behaviorBlock?
      collectionDefinition?
      ';'?
    ;

enumConstantList
    : enumConstant ( '|' enumConstant )*
    ;

enumConstant
    : name=qualifiedNameSegment
    ;

collectionDefinition
    : kind=('list' | 'set') collectionName=typeName? behaviorBlock?
    ;

behaviorBlock
    : 'behavior' '{' javaImports? behaviorMethod* '}'
    ;

javaImports
    : 'java' 'imports' '{' (qualifiedName ';')* '}'
    ;

behaviorMethod
    : (visibility=('public' | 'private') | effect=('read' | 'modify')) returnType=type name=methodName '(' parameterList? ')'
      ( '{' rawJavaBlock '}' | 'implemented' 'by' implementation=qualifiedName ';' )
    ;

validationBlock
    : '{' validationStatement* '}'
    ;

validationStatement
    : 'require' '(' condition=expression (',' message=STRING_LITERAL)? ')' ';'
    ;

// ==========================================
// 2. Events (outbox | memory | default)
// ==========================================
eventDefinition
    : (dispatchKind=eventDispatchKind)? 'event' name=typeName '(' parameterList? ')'
      ( '{' eventMember* '}' )?
      ';'?
    ;

eventDispatchKind
    : 'outbox'
    | 'memory'
    ;

eventMember
    : packageDeclarationStatement
    ;

// ==========================================
// 3. Aggregates & Entities
// ==========================================
aggregateDefinition
    : 'aggregate' name=typeName '[' idReference ']' '(' parameterList? ')'
      ( 'validates' validationBlock )?
      ( '{' aggregateMember* '}' )?
      behaviorBlock?
      collectionDefinition?
      ';'?
    ;

aggregateMember
    : packageDeclarationStatement
    | methodDefinition
    ;

idReference
    : idType=type
    ;

entityDefinition
    : 'entity' name=typeName '[' idReference ']' '(' parameterList? ')'
      ( 'validates' validationBlock )?
      ( '{' entityMember* '}' )?
      behaviorBlock?
      ( collectionDefinition )?
      ';'?
    ;

entityMember
    : packageDeclarationStatement
    | methodDefinition
    ;

invariantDefinition
    : 'invariant' name=IDENTIFIER '{' rawJavaBlock '}'
    ;

methodDefinition
    : accessModifier? returnType=type name=methodName '(' parameterList? ')' '{' rawJavaBlock '}'
    ;

accessModifier
    : 'public' | 'internal' | 'private'
    ;

// ==========================================
// 4. Ports (Outbound Adapters & Mapping)
// ==========================================
portDefinition
    : 'port' name=typeName '{' portMember* '}'
    ;

portMember
    : schemaDefinition
    | portMethodDefinition
    ;

schemaDefinition
    : 'schema' name=typeName '{' schemaField* '}'
    ;

schemaField
    : fieldType=type name=variableName ';'
    ;

portMethodDefinition
    : returnType=type name=methodName '(' parameterList? ')' throwsClause? '{'
        adapterDefinition
        mappingBlock?
      '}'
    ;

throwsClause
    : 'throws' qualifiedName (',' qualifiedName)*
    ;

adapterDefinition
    : adapterCustom
    | adapterRest
    ;

adapterRest
    : 'adapter' 'rest' '{' packageDeclarationStatement? restConfig* restErrorRule* '}'
    ;

adapterCustom
    : 'adapter' 'custom' delegateName=qualifiedName? ';'
    | 'adapter' 'custom' '{' packageDeclarationStatement? rawJavaBlock '}'
    | 'adapter' 'custom' delegateName=qualifiedName '{' packageDeclarationStatement? '}' ';'?
    ;

restConfig
    : httpMethod STRING_LITERAL ';'
    | variableName ':' STRING_LITERAL ';'
    ;

httpMethod
    : 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
    ;

restErrorRule
    : 'on' statusCode ( 'return' expression | 'throw' type ) ';'
    ;

statusCode
    : INT_LITERAL
    | INT_LITERAL '..' INT_LITERAL
    | STATUS_FAMILY
    ;

mappingBlock
    : 'mapping' '{' mappingStatement* '}'
    ;

mappingStatement
    : sourcePath ( '<-' | '->' ) targetPath ';'
    ;

sourcePath
    : variableName ( '.' variableName | '[' ( '*' | INT_LITERAL ) ']' )* ( ':' type )?
    ;

targetPath
    : variableName ( '.' variableName )*
    ;

// ==========================================
// 5. Repositories
// ==========================================
repositoryDefinition
    : 'repository' (name=typeName)? 'for' aggregateName=typeName '{'
        repositoryMember*
      '}' ';'?
    ;

repositoryMember
    : packageDeclarationStatement
    | repositoryFindMethod
    | repositoryCustomMethod
    ;

packageDeclarationStatement
    : 'package' qualifiedName ';'
    ;

repositoryFindMethod
    : 'find' returnType=type name=qualifiedNameSegment '(' parameterList? ')'
      ('where' repositoryExpression)?
      ('order' 'by' repositoryOrder (',' repositoryOrder)*)? ';'
    ;

repositoryExpression : repositoryAnd ('or' repositoryAnd)* ;
repositoryAnd : repositoryNot ('and' repositoryNot)* ;
repositoryNot : 'not' repositoryNot | '(' repositoryExpression ')' | repositoryPredicate ;
repositoryPredicate
    : field=qualifiedNameSegment ((operator=('=' | '!=' | '<' | '<=' | '>' | '>=' | 'like' | 'contains') | operator=('starts' | 'ends') 'with') ':' parameterName=variableName
        | 'is' presence=('absent' | 'present'))
    ;
repositoryOrder
    : field=qualifiedNameSegment direction=('asc' | 'desc')?
    ;

repositoryCustomMethod
    : 'custom' returnType=type name=methodName '(' parameterList? ')' ';'
    ;

// ==========================================
// 6. Use Cases
// ==========================================
usecaseDefinition
    : 'usecase' name=typeName '(' inputs=parameterList? ')'
      ('returns' (resultType=type | '(' results=parameterList ')'))?
      ('uses' dependencies+=parameter (',' dependencies+=parameter)*)?
      ('validates' validationBlock)?
      'behavior' '{' javaImports? usecaseBehaviorMember* '}' ';'?
    ;
usecaseBehaviorMember
    : 'execute' ('{' rawJavaBlock '}' | 'implemented' 'by' implementation=qualifiedName ';')
    | behaviorMethod
    ;

useDependencyStatement
    : 'use' typeName (variableName)? ';'
    ;

singleReturnStatement
    : 'return' expression? ';'
    ;

tupleReturnStatement
    : 'return' '(' tupleElement (',' tupleElement)* ')' ';'
    ;

tupleElement
    : expression ('as'? alias=variableName)?
    ;

rawJavaStatement
    : javaBlockStatement
    | '{' rawJavaBlock '}'
    | ~('}' | 'use' | 'load' | 'save' | 'return' | 'package' | 'emit' | '{') ~(';' | '{')* ';'
    ;

javaBlockStatement
    : ('if' | 'for' | 'while' | 'switch' | 'try') ~('{')* '{' rawJavaBlock '}' ( 'else' ( '{' rawJavaBlock '}' | javaBlockStatement ) )?
    ;

// ==========================================
// 7. Domain Services
// ==========================================
domainServiceDefinition
    : 'service' name=typeName
      ('uses' dependencies+=parameter (',' dependencies+=parameter)*)?
      'behavior' '{' javaImports? serviceMethod* '}' ';'?
    ;

serviceMethod
    : visibility=('public' | 'private') returnType=type name=methodName '(' parameterList? ')'
      ('validates' validationBlock)?
      ('{' rawJavaBlock '}' | 'implemented' 'by' implementation=qualifiedName ';')
    ;

// ==========================================
// 8. Event Listeners
// ==========================================
listenerDefinition
    : 'listener' eventName=typeName '{'
        listenerMember*
      '}'
      ';'?
    ;

listenerMember
    : packageDeclarationStatement
    | useDependencyStatement
    | rawJavaStatement
    ;

// ==========================================
// Gemeinsame Regeln & Ausdrücke
// ==========================================
parameterList
    : parameter (',' parameter)*
    ;

parameter
    : ( '@' typeName ( '(' STRING_LITERAL ')' )? )* (isMut='mut')? paramType=type (name=variableName)?
    ;

type
    : rawType=qualifiedName ('<' typeArguments '>')? isOptional='?'?
    ;

typeArguments
    : type (',' type)*
    ;

// Vernac keywords are contextual in qualified names. Java validity is checked semantically.
qualifiedName
    : qualifiedNameSegment ('.' qualifiedNameSegment)*
    ;

qualifiedNameSegment
    : IDENTIFIER
    | BOOLEAN_LITERAL
    | 'DELETE'
    | 'GET'
    | 'PATCH'
    | 'POST'
    | 'PUT'
    | 'adapter'
    | 'aggregate'
    | 'as'
    | 'by'
    | 'read' | 'modify' | 'behavior' | 'java' | 'imports' | 'implemented' | 'by' | 'collection'
    | 'list'
    | 'set'
    | 'custom'
    | 'else'
    | 'emit'
    | 'entity'
    | 'event'
    | 'find'
    | 'for'
    | 'from'
    | 'id'
    | 'if'
    | 'import'
    | 'internal'
    | 'invariant'
    | 'listener'
    | 'load'
    | 'mapping'
    | 'memory'
    | 'mut'
    | 'namespace'
    | 'on'
    | 'outbox'
    | 'package'
    | 'port'
    | 'private'
    | 'public'
    | 'repository'
    | 'returns' | 'uses' | 'execute' | 'or' | 'not' | 'where' | 'and' | 'order' | 'asc' | 'desc' | 'is' | 'absent' | 'present' | 'like' | 'contains' | 'starts' | 'ends' | 'with'
    | 'require'
    | 'rest'
    | 'return'
    | 'save'
    | 'schema'
    | 'service'
    | 'switch'
    | 'throw'
    | 'throws'
    | 'to'
    | 'try'
    | 'use'
    | 'usecase'
    | 'validates'
    | 'value'
    | 'while'
    ;

rawJavaBlock
    : rawJavaToken*
    ;

rawJavaToken
    : '{' rawJavaBlock '}'
    | ~('{' | '}')
    ;

expression
    : expression ( '&&' | '||' | '==' | '!=' | '<=' | '>=' | '<' | '>' ) expression
    | expression ( '+' | '-' | '*' | '/' ) expression
    | '!' expression
    | expression '.' variableName ( '(' argumentList? ')' )?
    | primaryExpression
    ;

primaryExpression
    : variableName ( '(' argumentList? ')' )?
    | STRING_LITERAL
    | INT_LITERAL
    | DECIMAL_LITERAL
    | BOOLEAN_LITERAL
    | '(' expression ')'
    ;

argumentList
    : expression (',' expression)*
    ;

// ==========================================
// Spezifische Namens-Kategorien
// ==========================================
typeName
    : IDENTIFIER
    ;

methodName
    : 'where' | 'and' | 'order' | 'asc' | 'desc' | 'is' | 'absent' | 'present' | 'like' | 'contains' | 'starts' | 'ends' | 'with'
    | IDENTIFIER
    | 'value'
    | 'id'
    ;

variableName
    : 'where' | 'and' | 'order' | 'asc' | 'desc' | 'is' | 'absent' | 'present' | 'like' | 'contains' | 'starts' | 'ends' | 'with'
    | IDENTIFIER
    | 'value'
    | 'id'
    ;

// ==========================================
// Lexer-Tokens
// ==========================================
IDENTIFIER      : IdentifierStart IdentifierPart* ;
fragment IdentifierStart
    : {org.vernac.language.VernacNames.isStart(_input.LA(1))
        || (org.vernac.language.VernacNames.isForbidden(_input.LA(1)) && !Character.isWhitespace(_input.LA(1)))}? .
    ;
fragment IdentifierPart
    : {org.vernac.language.VernacNames.isPart(_input.LA(1))
        || (org.vernac.language.VernacNames.isForbidden(_input.LA(1)) && !Character.isWhitespace(_input.LA(1)))}? .
    ;
STRING_LITERAL  : '"' (~["\\\r\n] | '\\' .)* '"' ;
INT_LITERAL     : [0-9]+ ;
DECIMAL_LITERAL : [0-9]+ '.' [0-9]+ ;
BOOLEAN_LITERAL : 'true' | 'false' ;

WS            : [ \t\r\n]+ -> skip ;
LINE_COMMENT  : '//' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;

STATUS_FAMILY   : [1-5] [xX] [xX] ;

ANY_CHAR      : . ;