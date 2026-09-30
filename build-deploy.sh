#!/bin/bash
set -e

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="1.0.0"
PROJECT="janusgraph-cli"
DEPLOY_NAME="${PROJECT}-${VERSION}"
DEPLOY_DIR="${1:-$APP_HOME/target/$DEPLOY_NAME}"

echo "========================================="
echo " JanusGraph CLI - Build & Deploy"
echo "========================================="

# ── 1. 自动检测 JDK 8 ──
if [ -z "$JAVA_HOME" ]; then
  if [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)
  fi
  if [ -z "$JAVA_HOME" ]; then
    for dir in /usr/lib/jvm/java-8-* /usr/lib/jvm/jdk-8* /usr/lib/jvm/jdk1.8*; do
      if [ -d "$dir" ]; then JAVA_HOME="$dir"; break; fi
    done
  fi
fi

if [ -z "$JAVA_HOME" ] || [ ! -f "$JAVA_HOME/bin/javac" ]; then
  echo "ERROR: JDK 8 not found. Set JAVA_HOME to a JDK 8 path."
  exit 1
fi
export JAVA_HOME
echo "Using: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"

# ── 2. 构建 ──
echo ""
echo "[1/2] Building project..."
cd "$APP_HOME"
mvn clean package -q -DskipTests
echo "  OK"

# ── 3. 制作部署包 ──
echo "[2/2] Creating deploy package..."
rm -rf "$DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR"

cp "target/$PROJECT-$VERSION.jar" "$DEPLOY_DIR/"
cp start-cli.sh "$DEPLOY_DIR/"
chmod +x "$DEPLOY_DIR/start-cli.sh"
cp src/main/resources/janusgraph-hbase.properties "$DEPLOY_DIR/"

for f in jaas.conf krb5.conf hbase.headless.keytab; do
  if [ -f "src/main/resources/$f" ]; then
    cp "src/main/resources/$f" "$DEPLOY_DIR/"
  fi
done

# tar.gz
cd "$(dirname "$DEPLOY_DIR")"
tar czf "${DEPLOY_NAME}.tar.gz" "$DEPLOY_NAME"

echo ""
echo "========================================="
echo " Deploy package ready!"
echo "========================================="
echo ""
echo "Directory: $DEPLOY_DIR"
ls -lh "$DEPLOY_DIR/"
echo ""
echo "Archive: $(dirname "$DEPLOY_DIR")/${DEPLOY_NAME}.tar.gz ($(du -sh "$(dirname "$DEPLOY_DIR")/${DEPLOY_NAME}.tar.gz" | cut -f1))"
echo ""
echo "Deploy:"
echo "  tar xzf ${DEPLOY_NAME}.tar.gz"
echo "  cd $DEPLOY_NAME"
echo "  ./start-cli.sh"
