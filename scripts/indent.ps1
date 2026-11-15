$rows = @(Get-ChildItem src -Recurse -Filter '*.java' | ForEach-Object {
    foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $_.FullName) {
        if ($line.Trim().Length -gt 0) {
            [pscustomobject]@{ Indent = [regex]::Match($line, '^[ \t]*').Length; Length = $line.Length }
        }
    }
})
[pscustomobject]@{
    MaxIndent = ($rows.Indent | Measure-Object -Maximum).Maximum
    IndentOver40 = @($rows | Where-Object Indent -gt 40).Count
    IndentOver60 = @($rows | Where-Object Indent -gt 60).Count
    LengthOver120 = @($rows | Where-Object Length -gt 120).Count
}
