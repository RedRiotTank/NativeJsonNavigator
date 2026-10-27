# Releasing

This project publishes to **Maven Central** through the
[Central Portal](https://central.sonatype.com/) using the
`central-publishing-maven-plugin`. Artifacts are signed with GPG, and the
release build produces the main jar plus `-sources` and `-javadoc` jars.

## One-time setup

### 1. Verify the namespace

1. Sign in to <https://central.sonatype.com/> with the GitHub account that owns
   this repository (`RedRiotTank`).
2. Add the namespace **`io.github.redriottank`**. GitHub-based namespaces are
   verified automatically while logged in with the owning account.
3. Generate a **user token** under *Account → Generate User Token*. The token
   consists of a username and a password.

### 2. Create a GPG signing key

```bash
gpg --full-generate-key          # RSA 4096, no expiry is fine
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
```

The public key must be discoverable by Maven Central. After the first
publication, verify it under *Account → Keys*.

### 3. Configure local credentials

`~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_PORTAL_TOKEN_USERNAME</username>
      <password>YOUR_PORTAL_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

### 4. Configure GitHub Actions secrets

Add these repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
| :--- | :--- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored private key (`gpg --armor --export-secret-keys <KEY_ID>`) |
| `MAVEN_GPG_PASSPHRASE` | Passphrase of the signing key |

## Cutting a release

### One command (recommended)

```bash
./scripts/release.sh 1.1.0 --wait
```

The script:

1. Refuses to run on a dirty or out-of-date `master`.
2. Runs `./mvnw -Prelease verify` (tests + GPG signing when the local key is
   available).
3. Updates the version in `pom.xml`, the `<scm><tag>` and the README snippets.
4. Commits `release: v1.1.0`, creates the `v1.1.0` tag and pushes both.
5. Creates the GitHub Release, which triggers the Central deployment.
6. With `--wait`, waits for the workflow and reports the result.

Options: `--notes <file>` for custom release notes, `--dry-run` to preview, and
`--yes` to skip confirmation. Add a `## [1.1.0]` section to `CHANGELOG.md`
first if you want a curated changelog.

### Guard rails

Both the script and `.github/workflows/release.yml` run
`scripts/validate-release.sh`, which fails the release when:

* the tag is not exactly `v` + the `pom.xml` version (for example release
  `v1.1.0` with `<version>1.0.0</version>`);
* the version is a `SNAPSHOT` or is not a valid release version;
* the groupId/artifactId are not
  `io.github.redriottank:native-json-navigator`;
* the version already exists on Maven Central (Central is immutable).

So even if a mismatch slips through locally, the GitHub workflow stops before
building or uploading anything.

### Manual release

1. Update `<version>` in `pom.xml` and the `<tag>` in `<scm>`.
2. Run the full build locally:

   ```bash
   ./mvnw -B verify
   ./mvnw -B -Prelease verify        # also checks GPG signing
   ```

3. Commit, tag and push:

   ```bash
   ./scripts/validate-release.sh --tag v1.1.0
   git commit -am "release: v1.1.0"
   git tag -a v1.1.0 -m "Native JSON Navigator 1.1.0"
   git push origin master --follow-tags
   ```

4. Publish a GitHub release for the tag. The
   `.github/workflows/release.yml` workflow then runs
   `./mvnw -Prelease deploy`, uploads the bundle to the Central Portal and waits
   until it is published.

Manual publication works the same way:

```bash
./mvnw -Prelease deploy
```

The release profile sets `autoPublish=true`, so a successful upload is
published automatically after Central's validation.

> A tag push alone does **not** publish anything. Only publishing a GitHub
> Release (or running the workflow manually) triggers the deployment.

## Verifying the release

```xml
<dependency>
    <groupId>io.github.redriottank</groupId>
    <artifactId>native-json-navigator</artifactId>
    <version>1.0.0</version>
</dependency>
```

* Artifact page: <https://central.sonatype.com/artifact/io.github.redriottank/native-json-navigator>
* Search: <https://central.sonatype.com/search?q=native-json-navigator>

Maven Central indexing can take a few minutes after publication.

## Alternative: GitHub Packages

The `github` profile is available for a quick internal deployment:

```bash
./mvnw -Pgithub deploy
```

This requires a `github` server entry in `settings.xml` (username plus a
personal access token with `write:packages`). Consumers of GitHub Packages must
authenticate even for public artifacts, so **Maven Central is the recommended
distribution channel**.
