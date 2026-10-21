param([Parameter(Mandatory=$true)][guid]$ProjectId)
$ErrorActionPreference='Stop'
# Emergency operator recovery requires direct access to the local PostgreSQL container.
# No API bypass or authentication-off switch is provided.
$bytes=New-Object byte[] 32
$random=[Security.Cryptography.RandomNumberGenerator]::Create()
try {$random.GetBytes($bytes)} finally {$random.Dispose()}
$key=[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
$sha=[Security.Cryptography.SHA256]::Create()
try {$hash=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($key)))).Replace('-','').ToLowerInvariant()} finally {$sha.Dispose()}
$id=[guid]::NewGuid().ToString()
$request=[guid]::NewGuid().ToString()
$project=$ProjectId.ToString()
$sql=@"
BEGIN;
DO `$`$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM team_access_state WHERE id=1 AND initialized=TRUE) THEN RAISE EXCEPTION 'Initialize access normally before operator recovery'; END IF;
 IF NOT EXISTS (SELECT 1 FROM projects WHERE id='$project') THEN RAISE EXCEPTION 'Project does not exist'; END IF;
END; `$`$;
INSERT INTO team_credentials(id,project_id,display_name,kind,role,token_hash,expires_at)
VALUES ('$id','$project','Recovered project owner','HUMAN','OWNER','$hash',clock_timestamp()+INTERVAL '30 days');
INSERT INTO team_audit_events(id,request_id,project_id,actor_id,actor_kind,actor_role,action,target,outcome)
VALUES (gen_random_uuid(),'$request','$project','$id','OPERATOR','OWNER','OPERATOR_OWNER_RECOVERY','$id','RECORDED');
COMMIT;
"@
try {
    $sql | & docker exec -i bugtrace-ai-postgres-1 sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    if($LASTEXITCODE -ne 0){throw 'Operator recovery failed; no key was copied.'}
    Set-Clipboard -Value $key
    Write-Host "Recovery recorded for project $project. The new owner key is on your clipboard, not in this output. Store it privately, sign in, and revoke obsolete credentials."
} finally {$key=$null;$sql=$null}
