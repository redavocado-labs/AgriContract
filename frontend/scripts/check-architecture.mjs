import path from "node:path"
import ts from "typescript"

// Each entry lists the feature APIs its consumers may use. Features expose these
// APIs through their own index.ts. Routes can compose feature internals directly.
const featureDependencies = new Map([
  ["arbitration", ["contracts", "milestones"]],
  ["audit", []],
  ["auth", ["organization"]],
  ["contracts", ["auth", "organization"]],
  ["milestones", ["auth", "contracts"]],
])

const root = process.cwd()
const configPath = path.join(root, "tsconfig.app.json")
const config = ts.readConfigFile(configPath, ts.sys.readFile)
if (config.error) throw new Error(ts.flattenDiagnosticMessageText(config.error.messageText, "\n"))
const { options, errors } = ts.parseJsonConfigFileContent(config.config, ts.sys, root)
if (errors.length) {
  throw new Error(errors.map((error) => ts.flattenDiagnosticMessageText(error.messageText, "\n")).join("\n"))
}

const extensions = [".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs"]
const files = ["src", "tests"].flatMap((directory) =>
  ts.sys.readDirectory(path.join(root, directory), extensions, ["**/node_modules/**"])
).sort()
const dependencies = new Map(files.map((file) => [file, new Set()]))
const resolutionCache = ts.createModuleResolutionCache(root, (name) => name, options)
const diagnostics = []
const relative = (file) => path.relative(root, file).split(path.sep).join("/")

function moduleReference(node) {
  if (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) {
    return node.moduleSpecifier
  }
  if (ts.isImportTypeNode(node) && ts.isLiteralTypeNode(node.argument)) {
    return node.argument.literal
  }
  if (ts.isExternalModuleReference(node)) return node.expression
  if (ts.isCallExpression(node) && (
    node.expression.kind === ts.SyntaxKind.ImportKeyword ||
    (ts.isIdentifier(node.expression) && node.expression.text === "require")
  )) {
    return node.arguments[0]
  }
}

function moduleReferences(contents) {
  const specifiers = new Set()
  function visit(node) {
    const reference = moduleReference(node)
    if (reference && ts.isStringLiteralLike(reference)) specifiers.add(reference.text)
    node.forEachChild(visit)
  }
  visit(contents)
  return specifiers
}

function boundaryProblem(source, destination) {
  if (!source.startsWith("src/")) return
  if (destination.startsWith("tests/")) return "Application modules cannot depend on tests"
  const [, sourceLayer, sourceFeature] = source.split("/")
  const [, destinationLayer, destinationFeature] = destination.split("/")
  if (sourceLayer === "shared" && destinationLayer !== "shared") {
    return "Shared modules can only depend on other shared modules"
  }
  if (sourceLayer !== "features" || destinationLayer === "shared") return
  if (destinationLayer !== "features") return "Features can only depend on features and shared modules"
  if (sourceFeature === destinationFeature) return
  if (!featureDependencies.get(sourceFeature)?.includes(destinationFeature)) {
    return `Feature ${sourceFeature} has no declared dependency on ${destinationFeature}`
  }
  if (destination !== `src/features/${destinationFeature}/index.ts`) {
    return `Import ${destinationFeature} through its public index.ts`
  }
}

function resolveLocalImport(specifier, file) {
  // Vite can load existing assets with a query suffix outside TypeScript's resolver.
  const [asset] = specifier.split("?")
  if (asset.startsWith("@/")) return path.resolve(root, "src", asset.slice(2))
  if (asset.startsWith(".")) return path.resolve(path.dirname(file), asset)
}

for (const file of files) {
  const source = relative(file)
  const contents = ts.createSourceFile(file, ts.sys.readFile(file), ts.ScriptTarget.Latest, true)
  for (const specifier of moduleReferences(contents)) {
    const { resolvedModule } = ts.resolveModuleName(specifier, file, options, ts.sys, resolutionCache)
    if (resolvedModule?.isExternalLibraryImport) continue
    const target = resolvedModule?.resolvedFileName ?? resolveLocalImport(specifier, file)
    // External packages and virtual Vite imports have no local path to check.
    if (!target) continue
    if (!ts.sys.fileExists(target)) {
      diagnostics.push(`Unresolved local import ${specifier} in ${source}`)
      continue
    }
    const destination = relative(target)
    const problem = boundaryProblem(source, destination)
    if (problem) diagnostics.push(`${problem}: ${source} -> ${destination}`)
    if (dependencies.has(target)) dependencies.get(file).add(target)
  }
}

const visited = new Set()
const active = new Set()
const stack = []
function visit(file) {
  if (active.has(file)) {
    const cycle = [...stack.slice(stack.indexOf(file)), file].map(relative)
    diagnostics.push(`Circular dependency: ${cycle.join(" -> ")}`)
    return
  }
  if (visited.has(file)) return
  visited.add(file)
  active.add(file)
  stack.push(file)
  for (const dependency of dependencies.get(file)) visit(dependency)
  stack.pop()
  active.delete(file)
}
for (const file of files) visit(file)

if (diagnostics.length) {
  console.error(diagnostics.join("\n"))
  process.exitCode = 1
} else {
  console.log(`Architecture check passed for ${files.length} modules.`)
}
