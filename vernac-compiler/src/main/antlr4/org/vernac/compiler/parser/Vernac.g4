grammar Vernac;

compilationUnit
    : packageDeclaration? importDeclaration* topLevelDefinition* EOF
    ;

packageDeclaration
    : 'package' qualifiedName ';'
    ;

importDeclaration
    : 'import' qualifiedName ('.' '*')? ';'
    ;

topLevelDefinition
    : valueDefinition
    | aggregateDefinition
    | eventDefinition
    | serviceDefinition
    ;

// ==========================================
// 1. Value Objects
// ==========================================
valueDefinition
    : 'value' name=identifier '(' parameterList? ')' ( 'validates' validationBlock )? ( ';' | blockBody? )
    ;

validationBlock
    : '{' validationStatement* '}'
    ;

validationStatement
    : 'require' '(' condition=expression (',' message=STRING_LITERAL)? ')' ';'
    ;

// ==========================================
// 2. Aggregates & Entities
// ==========================================
aggregateDefinition
    : 'aggregate' name=identifier '{' aggregateMember* '}'
    ;

aggregateMember
    : fieldDeclaration
    | invariantDefinition
    | entityDefinition
    | methodDefinition
    ;

entityDefinition
    : 'entity' name=identifier '{' entityMember* '}'
    ;

entityMember
    : fieldDeclaration
    | methodDefinition
    ;

fieldDeclaration
    : ( 'id:' | name=identifier ':' ) type ('=' defaultValue=expression)? ';'
    ;

invariantDefinition
    : 'invariant' name=identifier '{' rawJavaBlock '}'
    ;

methodDefinition
    : accessModifier? returnType=type name=identifier '(' parameterList? ')' '{' rawJavaBlock '}'
    ;

accessModifier
    : 'public' | 'internal' | 'private'
    ;

// ==========================================
// 3. Events
// ==========================================
eventDefinition
    : annotation* 'event' name=identifier '(' parameterList? ')' ';'
    ;

annotation
    : '@' name=identifier ( '(' annotationArgument? ')' )?
    ;

annotationArgument
    : identifier
    | STRING_LITERAL
    ;

// ==========================================
// 4. Services (ACL Ports & Mapping)
// ==========================================
serviceDefinition
    : annotation* 'service' name=identifier '{' serviceMember* '}'
    ;

serviceMember
    : externalSchemaDefinition
    | serviceMethodDefinition
    ;

externalSchemaDefinition
    : 'external' 'schema' name=identifier '{' ( identifier ':' type ';' )* '}'
    ;

serviceMethodDefinition
    : httpAnnotation returnType=type name=identifier '(' parameterList? ')' throwsClause? ( mappingBlock | ';' )
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
    : identifier ( '.' identifier | '[' ( '*' | INT_LITERAL ) ']' )* ( ':' type )?
    ;

targetPath
    : identifier ( '.' identifier )*
    ;

// ==========================================
// Gemeinsame Regeln & Ausdrücke
// ==========================================
parameterList
    : parameter (',' parameter)*
    ;

parameter
    : ( '@' identifier ( '(' STRING_LITERAL ')' )? )? type name=identifier
    ;

type
    : rawType=identifier ('<' typeArguments '>')? (isOptional='?')?
    ;

typeArguments
    : type (',' type)*
    ;

qualifiedName
    : identifier ('.' identifier)*
    ;

blockBody
    : '{' methodDefinition* '}'
    ;

rawJavaBlock
    : ( ~[{}] | '{' rawJavaBlock '}' )*
    ;

expression
    : expression ( '&&' | '||' | '==' | '!=' | '<=' | '>=' | '<' | '>' ) expression
    | expression ( '+' | '-' | '*' | '/' ) expression
    | '!' expression
    | primaryExpression
    ;

primaryExpression
    : qualifiedName ( '(' argumentList? ')' )?
    | STRING_LITERAL
    | INT_LITERAL
    | DECIMAL_LITERAL
    | BOOLEAN_LITERAL
    | '(' expression ')'
    ;

argumentList
    : expression (',' expression)*
    ;

// Kontextuelle Keywords als Identifier erlauben
identifier
    : IDENTIFIER
    | 'value'
    | 'aggregate'
    | 'entity'
    | 'event'
    | 'service'
    | 'invariant'
    | 'id'
    | 'mapping'
    ;

// Lexer-Tokens
IDENTIFIER      : [a-zA-Z_][a-zA-Z0-9_]* ;
STRING_LITERAL  : '"' (~["\\\r\n] | '\\' .)* '"' ;
INT_LITERAL     : [0-9]+ ;
DECIMAL_LITERAL : [0-9]+ '.' [0-9]+ ;
BOOLEAN_LITERAL : 'true' | 'false' ;

WS            : [ \t\r\n]+ -> skip ;
LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;