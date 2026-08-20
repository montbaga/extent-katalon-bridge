<#
.SYNOPSIS
    Downloads the four jars the Extent-Katalon Bridge needs into this Drivers folder.

.DESCRIPTION
    Some enterprises don't allow binary blobs in source control. If that's you,
    delete the four *.jar files from this folder, add "Drivers/*.jar" to
    .gitignore, and run this script as a setup/CI step instead - it downloads
    the exact same, checksum-verified jars from Maven Central.

    Only these four jars are needed to generate a report:
      - extentreports        the report engine itself
      - freemarker            templates the Spark HTML report
      - rxjava (rxjava3)      Extent's internal report-event pipeline
      - reactive-streams      rxjava3's own required dependency

    Gson and Lombok are NOT bundled despite both appearing in extentreports'
    own POM: Lombok only generates code at extentreports' own compile time
    (nothing in the shipped jar references it at runtime), and Gson is only
    needed by extentreports' JsonFormatter/JsonConfigLoader classes, which
    this bridge deliberately never uses (see ARCHITECTURE.md for why).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File Drivers/fetch-extent-jars.ps1
#>

$ErrorActionPreference = 'Stop'

$targetDir = $PSScriptRoot

$artifacts = @(
    @{ Name = 'extentreports';    Version = '5.1.2'; GroupPath = 'com/aventstack';          Sha1 = '8c9745739678f408fb5a59c9c242dcbac76f393f' },
    @{ Name = 'freemarker';       Version = '2.3.32'; GroupPath = 'org/freemarker';           Sha1 = '8607259125d4fbe982754227ea4f75dd513c6d19' },
    @{ Name = 'rxjava';           Version = '3.1.6';  GroupPath = 'io/reactivex/rxjava3';     Sha1 = '0fd824db2008535f59f2ac0c7bfe8d49fb7193e0' },
    @{ Name = 'reactive-streams'; Version = '1.0.4';  GroupPath = 'org/reactivestreams';      Sha1 = '3864a1320d97d7b045f729a326e1e077661f31b7' }
)

foreach ($artifact in $artifacts) {
    $name = $artifact.Name
    $version = $artifact.Version
    $jarFile = Join-Path $targetDir "$name-$version.jar"
    $url = "https://repo1.maven.org/maven2/$($artifact.GroupPath)/$name/$version/$name-$version.jar"

    Write-Host "Downloading $name $version ..."
    Invoke-WebRequest -Uri $url -OutFile $jarFile -UseBasicParsing

    $actualSha1 = (Get-FileHash -Path $jarFile -Algorithm SHA1).Hash.ToLower()
    if ($actualSha1 -ne $artifact.Sha1) {
        Remove-Item $jarFile -Force
        throw "SHA1 mismatch for $name $version. Expected $($artifact.Sha1), got $actualSha1. Aborting."
    }
    Write-Host "  OK  $jarFile (sha1 verified)"
}

Write-Host "Done. Restart Katalon Studio (or re-open the project) so it picks up the new Drivers/*.jar files."
