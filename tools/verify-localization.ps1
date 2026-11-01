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
$chinese=Read-Catalog (Join-Path $projectRoot 'app/src/main/res/values-zh-rCN')
if($base.Count -lt 400){throw 'Translation catalog unexpectedly empty or incomplete.'}
foreach($name in $base.Keys) {
    if(!$chinese.ContainsKey($name)){throw "Missing Chinese translation: $name"}
    if($base[$name] -ne $chinese[$name]){throw "Translation format mismatch: $name"}
}
foreach($name in $chinese.Keys){if(!$base.ContainsKey($name)){throw "Missing default translation: $name"}}
foreach($directory in @('app/src/main/java','diagnostics/src/main/java')) {
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -Recurse -File |Where-Object {$_.Extension -in @('.kt','.java')}) {
        $text=[IO.File]::ReadAllText($file.FullName)
        foreach($match in [regex]::Matches($text,'"(?:\\.|[^"\\])*"')) {
            if($match.Value -match '[\u3400-\u9fff]'){throw "Hardcoded UI text in $($file.Name); move it to resources."}
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
