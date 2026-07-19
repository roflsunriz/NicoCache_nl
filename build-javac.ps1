Set-Location "C:\NicoCache_nl"
$sources = Get-ChildItem -Path ".\src\dareka" -Recurse -Filter "*.java" |
    Where-Object { $_.Name -ne "package-info.java" } |
    ForEach-Object { $_.FullName }
javac --release 11 -encoding UTF-8 -Xlint:-options -d ".\src" $sources
jar cfm "NicoCache_nl.jar" ".\manifest-nl.mf" -C ".\src" dareka -C ".\src" native