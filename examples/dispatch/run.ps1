param(
    [string]$JdkHome = "$env:USERPROFILE\.jdks\openjdk-25.0.1",
    [switch]$Walkthrough,
    [switch]$DatabaseTests
)
$ErrorActionPreference = 'Stop'
if ($Walkthrough -and $DatabaseTests) { throw 'Choose Walkthrough or DatabaseTests, not both.' }
if (-not (Test-Path (Join-Path $JdkHome 'bin\java.exe'))) { throw "JDK not found: $JdkHome. Pass -JdkHome with your Java 25 directory." }
$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$env:Path"
Push-Location $PSScriptRoot
try {
    if ($DatabaseTests) {
        & docker compose --profile test up -d postgres-test
        if ($LASTEXITCODE -ne 0) { throw 'Could not start the test database.' }
        & mvn verify -Pdatabase-tests
    } elseif ($Walkthrough) {
        & mvn spring-boot:run '-Dspring-boot.run.arguments=--dispatch.walkthrough=true'
    } else {
        & mvn spring-boot:run
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
