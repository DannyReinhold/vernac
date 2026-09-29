grammar Vernac;

compilationUnit
    : packageDeclaration? importDeclaration* topLevelDeclaration* EOF
    ;

packageDeclaration
    : 'package' qualifiedName ';'
    ;

importDeclaration
    : 'import' qualifiedName ('.' '*')? ';'
    ;

topLevelDeclaration
    : valueDefinition
    | eventDefinition
    | aggregateDefinition
    | entityDefinition
    | serviceDefinition
    | externalSchemaDefinition
    | repositoryDefinition
    ;

// ==========================================
// 1. Value Objects
// ==========================================
valueDefinition
    : 'value' name=typeName '(' parameterList? ')'
      ( 'validates' validationBlock )?
      ( '{' valueMember* '}' )?
      ( collectionDefinition )?
      ';'?
    ;

valueMember
    : packageDeclarationStatement
    | methodDefinition
    ;

collectionDefinition
    : 'collection' collectionName=typeName? ( '{' methodDefinition* '}' )?
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
    : 'aggregate' name=typeName '[' idDefinition ']' '(' parameterList? ')'
      ( 'validates' validationBlock )?
      ( '{' aggregateMember* '}' )?
      ';'?
    ;

aggregateMember
    : packageDeclarationStatement
    | methodDefinition
    ;

idDefinition
    : idType=type (name=variableName)?
    ;

entityDefinition
    : 'entity' name=typeName '[' idDefinition ']' '(' parameterList? ')'
      ( 'validates' validationBlock )?
      ( '{' entityMember* '}' )?
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
// 4. Services (ACL Ports & Mapping)
// ==========================================
serviceDefinition
    : 'service' name=typeName '{' serviceMember* '}'
    ;

serviceMember
    : externalSchemaDefinition
    | serviceMethodDefinition
    ;

externalSchemaDefinition
    : 'external' 'schema' name=typeName '{' schemaField* '}'
    ;

schemaField
    : name=variableName ':' type ';'
    ;

serviceMethodDefinition
    : httpAnnotation returnType=type name=methodName '(' parameterList? ')' throwsClause? ( mappingBlock | ';' )
    ;

httpAnnotation
    : '@' ( 'Get' | 'Post' | 'Put' | 'Delete' | 'Operation' ) '(' STRING_LITERAL ')'
    ;

throwsClause
    : 'throws' qualifiedName (',' qualifiedName)*
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
    | tableDeclaration
    | repositoryFindMethod
    | repositoryCustomMethod
    ;

packageDeclarationStatement
    : 'package' qualifiedName ';'
    ;

tableDeclaration
    : 'table' ':' tableName=STRING_LITERAL ';'
    ;

repositoryFindMethod
    : 'find' returnType=type name=methodName '(' parameterList? ')' ';'
    ;

repositoryCustomMethod
    : 'custom' returnType=type name=methodName '(' parameterList? ')' ';'
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

// Package- und Import-Pfade dürfen nur aus echten Identifiern bestehen
qualifiedName
    : IDENTIFIER ('.' IDENTIFIER)*
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
    : IDENTIFIER
    ;

variableName
    : IDENTIFIER
    | 'value'
    | 'id'
    ;

// ==========================================
// Lexer-Tokens
// ==========================================
IDENTIFIER      : [a-zA-Z_][a-zA-Z0-9_]* ;
STRING_LITERAL  : '"' (~["\\\r\n] | '\\' .)* '"' ;
INT_LITERAL     : [0-9]+ ;
DECIMAL_LITERAL : [0-9]+ '.' [0-9]+ ;
BOOLEAN_LITERAL : 'true' | 'false' ;

WS            : [ \t\r\n]+ -> skip ;
LINE_COMMENT  : '//' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;

ANY_CHAR      : . ;