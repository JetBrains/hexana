echo "== Building Extension Lib =="
../gradlew :vscode-plugin:extension-lib:jsBrowserProductionWebpack

echo "== Building WebView =="
../gradlew :vscode-plugin:webview:jsBrowserProductionWebpack

echo "== Building plugin shim =="
cd vscode-plugin
npm install
npm run compile

echo "== Packaging =="
npx @vscode/vsce package --allow-missing-repository

echo "== Registering the plugin in VSCode =="
if ! which code >/dev/null 2>&1
then
    if ! which code-insiders  >/dev/null 2>&1
    then
      echo "Please register vscode/vscode-insiders in PATH (Help -> Show All Commands -> path)"
    else
      code-insiders --install-extension hexana-wasm-0.0.1.vsix
      cd ~
      code-insiders &
    fi
else
  code --install-extension hexana-wasm-0.0.1.vsix
  cd ~
  code &
fi
