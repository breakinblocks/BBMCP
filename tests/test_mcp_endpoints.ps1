param(
    [string]$BaseUri = 'http://localhost:8080/mcp',
    [string]$ChapterId = '6F3CA3EA0F8276C0',
    [string]$RecipeId = 'minecraft:iron_ingot_from_smelting_raw_iron',
    [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = 'Stop'
$requestId = 1
$records = [System.Collections.Generic.List[object]]::new()
$reportPath = Join-Path $PSScriptRoot 'mcp-endpoint-report.json'

function Invoke-McpRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][hashtable]$Params
    )

    $request = [ordered]@{
        jsonrpc = '2.0'
        id      = $script:requestId
        method  = $Method
        params  = $Params
    }
    $script:requestId++
    $body = $request | ConvertTo-Json -Depth 100 -Compress
    return Invoke-RestMethod -Uri $BaseUri -Method Post -ContentType 'application/json' -Body $body -TimeoutSec $TimeoutSeconds
}

function Get-ContentSummary {
    param([Parameter(Mandatory = $true)]$Result)

    $content = @($Result.content)
    $types = @($content | ForEach-Object { [string]$_.type })
    $imageBytes = @($content | Where-Object { $_.type -eq 'image' } | ForEach-Object {
        if ($null -eq $_.data) { 0 } else { ([string]$_.data).Length }
    })
    $texts = @($content | Where-Object { $_.type -eq 'text' } | ForEach-Object { [string]$_.text })
    $summary = [ordered]@{
        content_types = $types
        image_base64_lengths = $imageBytes
    }
    if ($texts.Count -gt 0) {
        $text = [string]::Join("`n", $texts)
        $summary.text = if ($text.Length -gt 400) { $text.Substring(0, 400) + '...' } else { $text }
    }
    return $summary
}

function Add-Record {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Kind,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)]$Details
    )

    $records.Add([pscustomobject]@{
        name = $Name
        kind = $Kind
        status = $Status
        details = $Details
    })
}

function Test-Protocol {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][hashtable]$Params
    )

    try {
        $response = Invoke-McpRequest -Method $Method -Params $Params
        $hasError = $null -ne $response.error
        $details = [ordered]@{
            request_id = $response.id
            has_error = $hasError
        }
        if ($hasError) {
            $details.error = $response.error
            Add-Record -Name $Name -Kind 'protocol' -Status 'FAIL' -Details $details
        } else {
            Add-Record -Name $Name -Kind 'protocol' -Status 'PASS' -Details $details
        }
        return $response
    } catch {
        Add-Record -Name $Name -Kind 'protocol' -Status 'FAIL' -Details ([ordered]@{ exception = $_.Exception.Message })
        return $null
    }
}

function Test-Tool {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][hashtable]$Arguments,
        [string]$RecordName = $Name
    )

    try {
        $response = Invoke-McpRequest -Method 'tools/call' -Params @{ name = $Name; arguments = $Arguments }
        if ($null -eq $response.result) {
            Add-Record -Name $RecordName -Kind 'tool' -Status 'FAIL' -Details ([ordered]@{ error = 'Missing JSON-RPC result'; response = $response })
            return $response
        }
        $result = $response.result
        $isError = [bool]$result.isError
        $details = [ordered]@{
            is_error = $isError
            content = Get-ContentSummary -Result $result
        }
        if ($Name -eq 'capture_recipe_card' -and $Arguments.save_png -eq $true -and $null -eq $result.structuredContent) {
            throw 'capture_recipe_card save_png=true response is missing structuredContent'
        }
        if (@('dump_recipes', 'analyze_recipe_complexity', 'check_recipe_cycles', 'find_underutilized_items') -contains $Name -and $Arguments.save_json -eq $true -and $null -eq $result.structuredContent) {
            throw "$Name save_json=true response is missing structuredContent"
        }
        if ($null -ne $result.structuredContent) {
            $structured = $result.structuredContent
            if ($null -ne $structured.count) { $details.count = $structured.count }
            if ($null -ne $structured.path) { $details.path = $structured.path }
            if ($null -ne $structured.width) { $details.width = $structured.width }
            if ($null -ne $structured.height) { $details.height = $structured.height }
            if ($null -ne $structured.viewer) { $details.viewer = $structured.viewer }
            if ($null -ne $structured.saved_to_screenshots) { $details.saved_to_screenshots = $structured.saved_to_screenshots }
            if ($null -ne $structured.screenshot_path) { $details.screenshot_path = $structured.screenshot_path }
            if ($Name -eq 'capture_recipe_card' -and $Arguments.save_png -eq $true) {
                if ($structured.saved_to_screenshots -ne $true) { throw 'capture_recipe_card did not confirm saved_to_screenshots=true' }
                if ([string]::IsNullOrWhiteSpace([string]$structured.screenshot_path)) {
                    throw 'capture_recipe_card did not return screenshot_path'
                }
                $savedFile = Get-Item -LiteralPath ([string]$structured.screenshot_path) -ErrorAction Stop
                if ($savedFile.PSIsContainer) { throw 'capture_recipe_card returned a directory instead of a screenshot file' }
                if ($savedFile.Length -le 0) { throw 'capture_recipe_card returned an empty screenshot file' }
            }
            if (@('dump_recipes', 'analyze_recipe_complexity', 'check_recipe_cycles', 'find_underutilized_items') -contains $Name -and $Arguments.save_json -eq $true) {
                if ($structured.saved_to_file -ne $true) { throw "$Name did not confirm saved_to_file=true" }
                if ([string]::IsNullOrWhiteSpace([string]$structured.file_path)) { throw "$Name did not return file_path" }
                $savedFile = Get-Item -LiteralPath ([string]$structured.file_path) -ErrorAction Stop
                if ($savedFile.PSIsContainer -or $savedFile.Length -le 0) { throw "$Name returned an invalid JSON dump file" }
                $savedJson = Get-Content -LiteralPath $savedFile.FullName -Raw -ErrorAction Stop | ConvertFrom-Json
                $expectedField = switch ($Name) {
                    'dump_recipes' { 'recipes' }
                    'analyze_recipe_complexity' { 'item_id' }
                    'check_recipe_cycles' { 'cycles' }
                    'find_underutilized_items' { 'items' }
                }
                if ($null -eq $savedJson.$expectedField) { throw "$Name dump is missing $expectedField" }
            }
            if ($null -ne $structured.opened) { $details.opened = $structured.opened }
            if ($null -ne $structured.reload_dispatched) { $details.reload_dispatched = $structured.reload_dispatched }
        }
        $status = if ($isError) { 'FAIL' } else { 'PASS' }
        Add-Record -Name $RecordName -Kind 'tool' -Status $status -Details $details
        return $response
    } catch {
        Add-Record -Name $RecordName -Kind 'tool' -Status 'FAIL' -Details ([ordered]@{ exception = $_.Exception.Message })
        return $null
    }
}

Write-Output "Testing NeoMCP at $BaseUri"

Test-Protocol -Name 'initialize' -Method 'initialize' -Params @{} | Out-Null
Test-Protocol -Name 'ping' -Method 'ping' -Params @{} | Out-Null
$initialToolsResponse = Test-Protocol -Name 'tools/list (initial)' -Method 'tools/list' -Params @{}

$expectedTools = @(
    'execute_command', 'list_mods', 'get_player_info', 'get_block_entity_data',
    'inspect_item_components', 'query_registry', 'read_latest_logs',
    'inject_kubejs_script', 'get_nearby_entities', 'list_loot_tables',
    'get_loot_table', 'search_loot_tables', 'open_quest_gui',
    'get_chapter_layout', 'export_chapter_canvas', 'take_screenshot',
    'update_take_screenshot', 'look_at', 'jump', 'move', 'interact',
    'get_action_status', 'cancel_action', 'recipe_capabilities',
    'find_recipes', 'get_recipe', 'view_recipe', 'get_recipe_tree',
    'get_item_usages', 'get_workstation_recipes', 'scan_for_loops',
    'capture_recipe_card', 'dump_recipes', 'analyze_recipe_complexity',
    'check_recipe_cycles', 'find_underutilized_items'
)
if ($null -ne $initialToolsResponse -and $null -ne $initialToolsResponse.result) {
    $advertisedTools = @($initialToolsResponse.result.tools | ForEach-Object { [string]$_.name })
    $missingTools = @($expectedTools | Where-Object { $_ -notin $advertisedTools })
    $toolDetails = [ordered]@{
        advertised_count = $advertisedTools.Count
        expected_count = $expectedTools.Count
        missing = $missingTools
    }
    Add-Record -Name 'tools/list (required catalog)' -Kind 'protocol' -Status $(if ($missingTools.Count -eq 0) { 'PASS' } else { 'FAIL' }) -Details $toolDetails
}

Test-Tool -Name 'execute_command' -Arguments @{ command = 'time query daytime' } | Out-Null
$playerResponse = Test-Tool -Name 'get_player_info' -Arguments @{}
if ($null -eq $playerResponse -or $null -eq $playerResponse.result.structuredContent) {
    throw 'get_player_info did not return structured player data; cannot create in-world fixtures'
}

$playerInfo = $playerResponse.result.structuredContent
$playerX = [int][math]::Floor([double]$playerInfo.x)
$playerY = [int][math]::Floor([double]$playerInfo.y)
$playerZ = [int][math]::Floor([double]$playerInfo.z)
$fixtureY = $playerY - 1

Test-Tool -Name 'execute_command' -RecordName 'execute_command (block entity fixture)' -Arguments @{
    command = "setblock $playerX $fixtureY $playerZ minecraft:chest replace"
} | Out-Null
Test-Tool -Name 'execute_command' -RecordName 'execute_command (held item fixture)' -Arguments @{
    command = 'item replace entity @s weapon.mainhand with minecraft:egg'
} | Out-Null
Start-Sleep -Milliseconds 750

Test-Tool -Name 'get_block_entity_data' -Arguments @{ x = $playerX; y = $fixtureY; z = $playerZ } | Out-Null
Test-Tool -Name 'inspect_item_components' -Arguments @{} | Out-Null
Test-Tool -Name 'query_registry' -Arguments @{ registry = 'minecraft:item'; namespace = 'minecraft' } | Out-Null
Test-Tool -Name 'read_latest_logs' -Arguments @{} | Out-Null
Test-Tool -Name 'get_nearby_entities' -Arguments @{
    x = [double]$playerInfo.x
    y = [double]$playerInfo.y
    z = [double]$playerInfo.z
    radius = 8.0
} | Out-Null

Test-Tool -Name 'list_loot_tables' -Arguments @{} | Out-Null
Test-Tool -Name 'get_loot_table' -Arguments @{ loot_table_id = 'minecraft:chests/simple_dungeon' } | Out-Null
Test-Tool -Name 'search_loot_tables' -Arguments @{ item_id = 'minecraft:iron_ingot' } | Out-Null

$lookResponse = Test-Tool -Name 'look_at' -Arguments @{
    x = [double]$playerX + 3.0
    y = [double]$playerY + 1.0
    z = [double]$playerZ
    duration_ticks = 2
}
Start-Sleep -Milliseconds 500
if ($null -ne $lookResponse -and $null -ne $lookResponse.result.structuredContent.action_id) {
    Test-Tool -Name 'get_action_status' -RecordName 'get_action_status (look_at)' -Arguments @{
        action_id = [long]$lookResponse.result.structuredContent.action_id
    } | Out-Null
}
Test-Tool -Name 'jump' -Arguments @{} | Out-Null
$moveResponse = Test-Tool -Name 'move' -Arguments @{ direction = 'forward'; duration_ticks = 2 }
Start-Sleep -Milliseconds 500
if ($null -ne $moveResponse -and $null -ne $moveResponse.result.structuredContent.action_id) {
    Test-Tool -Name 'get_action_status' -RecordName 'get_action_status (move)' -Arguments @{
        action_id = [long]$moveResponse.result.structuredContent.action_id
    } | Out-Null
}
Test-Tool -Name 'interact' -Arguments @{ target = 'air'; hand = 'main_hand' } | Out-Null
$cancelStart = Test-Tool -Name 'move' -RecordName 'move (cancellation fixture)' -Arguments @{ direction = 'backward'; duration_ticks = 200 }
if ($null -ne $cancelStart -and $null -ne $cancelStart.result.structuredContent.action_id) {
    $cancelId = [long]$cancelStart.result.structuredContent.action_id
    Test-Tool -Name 'cancel_action' -Arguments @{ action_id = $cancelId } | Out-Null
    Test-Tool -Name 'get_action_status' -RecordName 'get_action_status (cancelled)' -Arguments @{ action_id = $cancelId } | Out-Null
}

Test-Tool -Name 'open_quest_gui' -Arguments @{ id = $ChapterId; object_type = 'chapter' } | Out-Null
Test-Tool -Name 'get_chapter_layout' -Arguments @{ chapter_id = $ChapterId } | Out-Null
$canvasResponse = Test-Tool -Name 'export_chapter_canvas' -Arguments @{ chapter_id = $ChapterId; save_png = $true }
Test-Tool -Name 'take_screenshot' -Arguments @{} | Out-Null
Test-Tool -Name 'update_take_screenshot' -Arguments @{} | Out-Null

Test-Tool -Name 'recipe_capabilities' -Arguments @{} | Out-Null
Test-Tool -Name 'find_recipes' -Arguments @{ query = 'iron_ingot'; limit = 5 } | Out-Null
Test-Tool -Name 'get_recipe' -Arguments @{ recipe_id = $RecipeId } | Out-Null
Test-Tool -Name 'view_recipe' -Arguments @{ recipe_id = $RecipeId; viewer = 'jei'; mode = 'recipe' } | Out-Null
Test-Tool -Name 'get_recipe_tree' -Arguments @{ item_id = 'minecraft:iron_ingot'; max_depth = 2 } | Out-Null
Test-Tool -Name 'get_item_usages' -Arguments @{ item_id = 'minecraft:iron_ingot' } | Out-Null
Test-Tool -Name 'get_workstation_recipes' -Arguments @{ machine_id = 'minecraft:crafting_table' } | Out-Null
Test-Tool -Name 'scan_for_loops' -Arguments @{ item_id = 'minecraft:iron_ingot'; max_depth = 5 } | Out-Null
Test-Tool -Name 'capture_recipe_card' -Arguments @{ recipe_id = $RecipeId; save_png = $true } | Out-Null
Test-Tool -Name 'dump_recipes' -Arguments @{ mod_namespace = 'minecraft'; recipe_type = ''; save_json = $true } | Out-Null
Test-Tool -Name 'analyze_recipe_complexity' -Arguments @{ item_id = 'minecraft:iron_ingot'; save_json = $true } | Out-Null
Test-Tool -Name 'check_recipe_cycles' -Arguments @{ item_id = 'minecraft:iron_ingot'; save_json = $true } | Out-Null
Test-Tool -Name 'find_underutilized_items' -Arguments @{ mod_namespace = 'minecraft'; save_json = $true } | Out-Null

$injectedScript = @'
NeoMcpEvents.register(event => {
  event.registerTool(
    "neomcp_endpoint_test",
    "Returns the active dimension for endpoint verification.",
    { type: "object", additionalProperties: false },
    context => ({ dimension: String(context.level.dimension) })
  );
});
'@
Test-Tool -Name 'inject_kubejs_script' -Arguments @{ script = $injectedScript } | Out-Null
Start-Sleep -Seconds 3
$reloadedToolsResponse = Test-Protocol -Name 'tools/list (after KubeJS reload)' -Method 'tools/list' -Params @{}
if ($null -ne $reloadedToolsResponse -and $null -ne $reloadedToolsResponse.result) {
    $reloadedNames = @($reloadedToolsResponse.result.tools | ForEach-Object { [string]$_.name })
    $dynamicDetails = [ordered]@{ present = 'neomcp_endpoint_test' -in $reloadedNames; advertised_count = $reloadedNames.Count }
    Add-Record -Name 'dynamic KubeJS tool publication' -Kind 'tool' -Status $(if ($dynamicDetails.present) { 'PASS' } else { 'FAIL' }) -Details $dynamicDetails
    if ($dynamicDetails.present) {
        Test-Tool -Name 'neomcp_endpoint_test' -Arguments @{} | Out-Null
    }
}

Test-Tool -Name 'execute_command' -RecordName 'execute_command (fixture cleanup)' -Arguments @{
    command = "setblock $playerX $fixtureY $playerZ minecraft:air replace"
} | Out-Null

$passed = @($records | Where-Object { $_.status -eq 'PASS' }).Count
$failed = @($records | Where-Object { $_.status -eq 'FAIL' }).Count
$report = [ordered]@{
    generated_at_utc = [DateTime]::UtcNow.ToString('o')
    endpoint = $BaseUri
    chapter_id = $ChapterId
    recipe_id = $RecipeId
    passed = $passed
    failed = $failed
    checks = $records
}
$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Output "Report written to $reportPath"
Write-Output "Passed: $passed; Failed: $failed"
if ($failed -gt 0) {
    exit 1
}
