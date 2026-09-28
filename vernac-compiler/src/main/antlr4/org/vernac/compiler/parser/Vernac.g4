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
    : 'value' name=identifier '(' parameterList? ')'
      ( 'validates' validationBlock )?
      ( blockBody )?
      ( 'collection' collectionName=identifier? ( '{' methodDefinition* '}' )? )?
      ';'?
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
    : 'aggregate' name=identifier '[' idDefinition ']' '(' parameterList? ')'
      ( 'validates' validationBlock )?
      ( blockBody )?
      ';'?
    ;

idDefinition
    : idType=type (name=identifier)?
    ;

entityDefinition
    : 'entity' name=identifier '[' idDefinition ']' '(' parameterList? ')'
      ( 'validates' validationBlock )?
      ( blockBody )?
      ';'?
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
    : '@' name=identifier ( '(' annotationArgumentList? ')' )?
    ;

annotationArgumentList
    : annotationPair (',' annotationPair)*
    | annotationValue
    ;

annotationPair
    : key=identifier '=' value=annotationValue
    ;

annotationValue
    : identifier
    | STRING_LITERAL
    | INT_LITERAL
    | BOOLEAN_LITERAL
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
    : 'external' 'schema' name=identifier '{' schemaField* '}'
    ;

schemaField
    : name=identifier ':' type ';'
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
// 5. Repositories
// ==========================================
repositoryDefinition
    : 'repository' name=identifier 'for' aggregateName=identifier '{'
        repositoryMember*
      '}' ';'?
    ;

repositoryMember
    : tableDeclaration
    | repositoryFindMethod
    | repositoryCustomMethod
    ;

tableDeclaration
    : 'table' ':' tableName=STRING_LITERAL ';'
    ;

repositoryFindMethod
    : 'find' returnType=type name=identifier '(' parameterList? ')' ';'
    ;

repositoryCustomMethod
    : 'custom' returnType=type name=identifier '(' parameterList? ')' ';'
    ;

// ==========================================
// Gemeinsame Regeln & Ausdrücke
// ==========================================
parameterList
    : parameter (',' parameter)*
    ;

parameter
    : ( '@' identifier ( '(' STRING_LITERAL ')' )? )* (isMut='mut')? paramType=type name=identifier
    ;

type
    : rawType=identifier ('<' typeArguments '>')? isOptional='?'?
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
    | expression '.' identifier ( '(' argumentList? ')' )?   // Chaining: .amount(), .compareTo(...), .ZERO
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
    | 'external'
    | 'schema'
    | 'repository'
    | 'for'
    | 'table'
    | 'find'
    | 'custom'
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
LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;

ANY_CHAR      : . ;