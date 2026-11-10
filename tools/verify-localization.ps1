$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
function Read-Catalog([string]$Directory) {
    $catalog=@{}
    foreach($file in Get-ChildItem -LiteralPath $Directory -Filter '*.xml' -File) {
        $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
        $reader=[Xml.XmlReader]::Create($file.FullName,$settings)
        try {$document=[Xml.XmlDocument]::new();$document.XmlResolver=$null;$document.Load($reader)} finally {$reader.Dispose()}
        foreach($element in $document.resources.ChildNodes) {
            if($element.LocalName -notin @('string','plurals')){continue}
            $name=$element.GetAttribute('name')
            if($catalog.ContainsKey($name)){throw "Duplicate resource: $name"}
            if($element.GetAttribute('translatable') -eq 'false'){continue}
            $text=if($element.LocalName -eq 'plurals'){($element.GetElementsByTagName('item')|Where-Object {$_.GetAttribute('quantity') -eq 'other'}).InnerText}else{$element.InnerText}
            $signature=([regex]::Matches([string]$text,'%[1-9][0-9]*\$[sdf]')|ForEach-Object {$_.Value}|Sort-Object -Unique)-join ','
            $catalog[$name]=$signature
        }
    }
    return ,$catalog
}
$base=Read-Catalog (Join-Path $projectRoot 'app/src/main/res/values')
if($base.Count -lt 400){throw 'Translation catalog unexpectedly empty or incomplete.'}
foreach($folder in @('values-zh-rCN','values-ja')) {
    $translated=Read-Catalog (Join-Path $projectRoot "app/src/main/res/$folder")
    foreach($name in $base.Keys) {
        if(!$translated.ContainsKey($name)){throw "Missing translation in ${folder}: $name"}
        if($base[$name] -ne $translated[$name]){throw "Translation format mismatch in ${folder}: $name"}
    }
    foreach($name in $translated.Keys){if(!$base.ContainsKey($name)){throw "Missing default translation: $name"}}
}
# These files hold names rather than copy: each language's own name, and place and service names.
$nameTables=@('Localization.kt','ExitInfo.kt','ServiceCatalogue.kt')
foreach($directory in @('app/src/main/java','diagnostics/src/main/java')) {
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -Recurse -File |Where-Object {$_.Extension -in @('.kt','.java')}) {
        $text=[IO.File]::ReadAllText($file.FullName)
        foreach($match in [regex]::Matches($text,'"(?:\\.|[^"\\])*"')) {
            if($file.Name -notin $nameTables -and $match.Value -match '[\u3400-\u9fff]'){throw "Hardcoded UI text in $($file.Name); move it to resources."}
        }
        # Android's XML HardcodedText rule does not cover every Compose call.
        # Permit branding, shell/protocol examples, numbers and SI units only.
        foreach($match in [regex]::Matches($text,'\bText\s*\(\s*"(?<literal>(?:\\.|[^"\\])*)"')) {
            $literal=$match.Groups['literal'].Value
            if($literal.StartsWith('DOMAIN-SUFFIX,example.com,') -or $literal -match '^\d[\w.\-]* · GPL-'){continue}
            $fixed=[regex]::Replace($literal,'\$\{[^}]*\}','')
            $fixed=[regex]::Replace($fixed,'\b(PingClash|P|sh|HTTP|ms|s)\b','')
            if($fixed -match '[A-Za-z]'){throw "Hardcoded Compose text in $($file.Name); use a string resource."}
        }
    }
}
Write-Output "Localization: $($base.Count) translated resources; placeholder parity and source-text guard passed."
