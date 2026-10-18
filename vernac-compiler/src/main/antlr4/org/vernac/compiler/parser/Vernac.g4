grammar Vernac;

applicationDefinition
    : 'application' name=qualifiedName '{' applicationEntry* '}' EOF
    ;
applicationEntry
    : 'activate' moduleName=qualifiedName ';'
    | 'bind' port=qualifiedName ('to' adapter=qualifiedName | 'from' 'configuration' property=STRING_LITERAL) ';'
    ;

moduleDefinition
    : 'module' name=qualifiedName '{' moduleExport* '}' EOF
    ;
moduleExport : 'export' exportedType=qualifiedName ';' ;

compilationUnit
    : namespaceDeclaration importDeclaration* topLevelDeclaration* EOF
    ;

namespaceDeclaration
    : 'namespace' qualifiedName ';'
    ;

importDeclaration
    : 'import' qualifiedName ('.' '*')? ';'
    ;

termDefinition : 'term' (name=qualifiedNameSegment | label=STRING_LITERAL) '{' termEntry* '}' ';'? ;
termEntry : kind=('description' | 'example' | 'note') text=STRING_LITERAL ';' ;

topLevelDeclaration
    : termDefinition
    | idDeclaration
    | valueDefinition
    | eventDefinition
    | aggregateDefinition
    | entityDefinition
    | portDefinition
    | inboundHttpAdapterDefinition
    | outboundAdapterDefinition
    | repositoryDefinition
    | usecaseDefinition
    | domainServiceDefinition
    | listenerDefinition
    ;

// ==========================================
// 0. Identifier Types
// ==========================================
idDeclaration
    : 'id' name=typeName (collectionDefinition (',' collectionDefinition)*)? ';'?
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
      (collectionDefinition (',' collectionDefinition)*)?
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
// 2. Domain events
// ==========================================
eventDefinition
    : recorded='recorded'? 'event' name=typeName '(' parameterList? ')'
      ('validates' validationBlock)? ';'?
    ;

// ==========================================
// 3. Aggregates & Entities
// ==========================================
aggregateDefinition
    : 'aggregate' name=typeName '[' idReference ']' '(' parameterList? ')'
      ( 'validates' validationBlock )?
      behaviorBlock?
      (collectionDefinition (',' collectionDefinition)*)?
      ';'?
    ;

idReference
    : idType=type
    ;

entityDefinition
    : 'entity' name=typeName '[' idReference ']' '(' parameterList? ')'
      ( 'validates' validationBlock )?
      behaviorBlock?
      (collectionDefinition (',' collectionDefinition)*)?
      ';'?
    ;

// ==========================================
// 4. Ports (Outbound Adapters & Mapping)
// ==========================================
portDefinition
    : 'port' name=typeName '{' portMethodDefinition* '}'
    ;

outboundAdapterDefinition
    : 'adapter' name=typeName 'implements' portType=type
      ('uses' dependencies+=parameter (',' dependencies+=parameter)*)?
      '{' configurationBlock? (schemaDefinition | mappingDefinition)*
      ('behavior' '{' javaImports? javaUses? serviceMethod* '}' | restBlock)
      (schemaDefinition | mappingDefinition)* '}' ';'?
    ;

inboundHttpAdapterDefinition
    : 'adapter' name=typeName 'inbound' transport=('http' | 'mvc')
      ('uses' dependencies+=parameter (',' dependencies+=parameter)*)?
      '{' 'base' base=STRING_LITERAL ';'
      ('error' 'view' errorView=STRING_LITERAL ';')?
      (inboundEndpoint | schemaDefinition | mappingDefinition)*
      inboundErrorPolicy? '}' ';'?
    ;
inboundEndpoint
    : name=methodName '{' verb=('GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE') route=STRING_LITERAL ';'
      inboundBinding* ('body' bodyType=type ';')?
      'execute' dependency=variableName '{' mappingAssignment* '}'
      ('respond' status=INT_LITERAL (responseType=type 'using' responseMapping=methodName)? ';'
       | 'render' view=STRING_LITERAL ('model' modelName=variableName)? ('status' renderStatus=INT_LITERAL)? ';'
       | 'redirect' redirect=STRING_LITERAL ('message' notice=STRING_LITERAL)? ';')
      ('on' 'absent' ('respond' absentStatus=INT_LITERAL
       | 'render' absentView=STRING_LITERAL 'status' absentStatus=INT_LITERAL) ';')? inboundErrorPolicy? '}'
    ;
inboundErrorPolicy
    : 'errors' ('implemented' 'by' implementation=qualifiedName ';'
      | '{' inboundErrorRule+ ('otherwise' 'default' ';')? '}')
    ;
inboundErrorRule
    : 'when' inboundErrorSelector 'then'
      ('respond' status=INT_LITERAL | 'render' view=STRING_LITERAL 'status' status=INT_LITERAL
       | 'redisplay' reload=methodName 'status' status=INT_LITERAL)
      (code=STRING_LITERAL message=STRING_LITERAL)? ';'
    ;
inboundErrorSelector
    : 'rejection' rejectionCode=qualifiedName
    | 'integration' responsibility=('local' | 'remote' | 'unknown') category=variableName
    | 'invalid' ('request' | 'cursor')
    | 'validation'
    | 'not' 'found'
    | 'optimistic' 'conflict'
    | 'domain' 'rejection'
    ;
inboundBinding
    : sensitive='sensitive'? kind=('path' | 'query' | 'header' | 'form') valueType=type name=variableName?
      ('named' externalName=STRING_LITERAL)? ('default' defaultValue=STRING_LITERAL)? ';'
    ;

configurationBlock
    : 'configuration' '{' configurationEntry+ '}'
    ;
configurationEntry
    : configType=qualifiedName name=variableName 'from' property=STRING_LITERAL ';'
    ;

restBlock
    : 'rest' '{' 'client' client=variableName ';' 'base' base=variableName ';' restOperation* '}'
    ;
restOperation
    : returnType=type name=methodName '(' parameterList? ')' '{'
      verb=('GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE') route=STRING_LITERAL ';'
      restBinding*
      ('body' '<-' requestPath=qualifiedName 'using' requestMapping=methodName ';')?
      ('response' responseType=type 'using' responseMapping=methodName ';')?
      ('on' absentStatus=INT_LITERAL 'absent' ';')?
      ('errors' ('implemented' 'by' errorImplementation=qualifiedName ';' | restErrors))?
      '}'
    ;
restErrors
    : '{' ('response' errorType=type ';')? restErrorRule+ ('otherwise' 'default' ';')? '}'
    ;
restErrorRule
    : 'when' restErrorOr 'then' restErrorAction ';'
    ;
restErrorOr : restErrorAnd ('or' restErrorAnd)* ;
restErrorAnd : restErrorNot ('and' restErrorNot)* ;
restErrorNot : 'not' restErrorNot | '(' restErrorOr ')' | restErrorPredicate ;
restErrorPredicate
    : field=qualifiedName (operator=('=' | '!=' | '<' | '<=' | '>' | '>=') restErrorLiteral
      | 'is' presence=('absent' | 'present'))
    ;
restErrorLiteral : STRING_LITERAL | booleanValue=('true' | 'false') | '-'? (INT_LITERAL | DECIMAL_LITERAL) ;
restErrorAction
    : 'reject' code=qualifiedName message=STRING_LITERAL
    | 'fail' responsibility=('local' | 'remote' | 'unknown') category=qualifiedName message=STRING_LITERAL
    ;
restBinding
    : kind=('path' | 'query' | 'header') target=STRING_LITERAL '<-' source=qualifiedName ';'
    ;

mappingDefinition
    : 'mapping' name=methodName ':' targetType=pageType '<-' sourceType=pageType
      ('{' (mappingAssignment* | javaImports? 'java' '{' rawJavaBlock '}') '}'
       | 'implemented' 'by' implementation=qualifiedName ';')
    ;
mappingAssignment
    : target=variableName '<-' path=qualifiedName ('using' mapper=methodName)? ';'
    ;

schemaDefinition
    : 'schema' name=typeName ('naming' strategy=variableName)? '(' (schemaField (',' schemaField)*)? ')' ';'
    ;

schemaField
    : patch='patch'? fieldType=pageType name=variableName? ('named' externalName=STRING_LITERAL)?
      ('absent' 'as' explicitNull='null')?
    ;

javaUses
    : 'java' 'uses' '{' (dependencies+=parameter ';')* '}'
    ;

portMethodDefinition
    : returnType=type name=methodName '(' parameterList? ')' ';'
    ;

// ==========================================
// 5. Repositories
// ==========================================
repositoryDefinition
    : 'repository' (name=typeName)? 'for' aggregateName=typeName '{'
        repositoryFindMethod*
      '}' ';'?
    ;

repositoryFindMethod
    : 'find' pagination=('offset' | 'cursor')? returnType=type name=qualifiedNameSegment '(' parameterList? ')'
      ('where' repositoryExpression)?
      ('order' 'by' repositoryOrder (',' repositoryOrder)*)? counting='counting'? ';'
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

// ==========================================
// 6. Use Cases
// ==========================================
usecaseDefinition
    : 'usecase' name=typeName '(' inputs=parameterList? ')'
      ('returns' (resultType=pageType | '(' results=resultParameterList ')'))?
      ('uses' dependencies+=parameter (',' dependencies+=parameter)*)?
      ('validates' validationBlock)?
      'behavior' '{' javaImports? usecaseBehaviorMember* '}' ';'?
    ;
usecaseBehaviorMember
    : 'execute' ('{' rawJavaBlock '}' | 'implemented' 'by' implementation=qualifiedName ';')
    | behaviorMethod
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
    : deliveryMode=('synchronous' | 'deferred') 'listener' name=typeName 'on' eventType=type
      ('uses' dependencies+=parameter (',' dependencies+=parameter)*)?
      'behavior' '{' javaImports? usecaseBehaviorMember* '}' ';'?
    ;

// ==========================================
// Gemeinsame Regeln & Ausdrücke
// ==========================================
parameterList
    : parameter (',' parameter)*
    ;

parameter
    : (isMut='mut')? paramType=type (name=variableName)?
    ;

type
    : rawType=qualifiedName ('<' typeArguments '>')? isOptional='?'?
    ;

pageType : (counted='counted' 'offset' | pageMode=('offset' | 'cursor'))? type ;
resultParameterList : resultParameter (',' resultParameter)* ;
resultParameter : isMut='mut'? paramType=pageType name=variableName? ;

typeArguments
    : type (',' type)*
    ;

// Vernac keywords are contextual in qualified names. Java validity is checked semantically.
qualifiedName
    : qualifiedNameSegment ('.' qualifiedNameSegment)*
    ;

qualifiedNameSegment
    : 'mvc' | 'render' | 'redirect' | 'redisplay' | 'model' | 'form' | 'error' | 'view' | 'message' | 'sensitive'
    | 'term' | 'description' | 'example' | 'note' | 'application' | 'activate' | 'bind' | 'to' | 'module' | 'export' | 'rejection' | 'integration' | 'invalid' | 'request' | 'validation' | 'found' | 'optimistic' | 'conflict' | 'domain' | 'counted' | 'cursor' | 'offset' | 'counting' | 'inbound' | 'http' | 'respond' | 'when' | 'then' | 'otherwise' | 'default' | 'reject' | 'fail' | 'local' | 'remote' | 'unknown' | 'status' | 'client' | 'base' | 'body' | 'response' | 'errors' | 'path' | 'query' | 'header'
    | IDENTIFIER
    | BOOLEAN_LITERAL
    | 'null'
    | 'DELETE'
    | 'GET'
    | 'PATCH'
    | 'POST'
    | 'PUT'
    | 'adapter'
    | 'aggregate'
    | 'as'
    | 'by'
    | 'configuration' | 'using' | 'naming' | 'named' | 'patch' | 'as' | 'null' | 'implements' | 'read' | 'modify' | 'behavior' | 'java' | 'imports' | 'implemented' | 'by' | 'collection'
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
    | 'recorded'
    | 'synchronous'
    | 'deferred'
    | 'on'
    | 'load'
    | 'mapping'
    | 'memory'
    | 'mut'
    | 'namespace'
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
    | 'null'
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
    : 'mvc' | 'render' | 'redirect' | 'redisplay' | 'model' | 'form' | 'error' | 'view' | 'message' | 'sensitive'
    | 'term' | 'description' | 'example' | 'note' | 'application' | 'activate' | 'bind' | 'to' | 'module' | 'export' | 'rejection' | 'integration' | 'invalid' | 'request' | 'validation' | 'found' | 'optimistic' | 'conflict' | 'domain' | 'counted' | 'cursor' | 'offset' | 'counting' | 'inbound' | 'http' | 'respond' | 'find' | 'when' | 'then' | 'otherwise' | 'default' | 'reject' | 'fail' | 'local' | 'remote' | 'unknown' | 'status' | 'client' | 'base' | 'body' | 'response' | 'errors' | 'path' | 'query' | 'header'
    | 'configuration' | 'using' | 'naming' | 'named' | 'patch'
    | 'read' | 'modify' | 'load' | 'save'
    | 'where' | 'and' | 'order' | 'asc' | 'desc' | 'is' | 'absent' | 'present' | 'like' | 'contains' | 'starts' | 'ends' | 'with'
    | IDENTIFIER
    | 'value'
    | 'id'
    ;

variableName
    : 'mvc' | 'render' | 'redirect' | 'redisplay' | 'model' | 'form' | 'error' | 'view' | 'message' | 'sensitive' | 'read' | 'modify'
    | 'term' | 'description' | 'example' | 'note' | 'application' | 'activate' | 'bind' | 'to' | 'module' | 'export' | 'rejection' | 'integration' | 'invalid' | 'request' | 'validation' | 'found' | 'optimistic' | 'conflict' | 'domain' | 'counted' | 'cursor' | 'offset' | 'counting' | 'inbound' | 'http' | 'respond' | 'find' | 'when' | 'then' | 'otherwise' | 'default' | 'reject' | 'fail' | 'local' | 'remote' | 'unknown' | 'status' | 'client' | 'base' | 'body' | 'response' | 'errors' | 'path' | 'query' | 'header'
    | 'configuration' | 'using' | 'naming' | 'named' | 'patch'
    | 'where' | 'and' | 'order' | 'asc' | 'desc' | 'is' | 'absent' | 'present' | 'like' | 'contains' | 'starts' | 'ends' | 'with'
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

ANY_CHAR      : . ;