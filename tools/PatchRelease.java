import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Patches only the version-pinned AI modules; no Minecraft recompilation. */
public final class PatchRelease implements Opcodes {
    private static final String NIM = "com/booke/nim/NimHttp";
    private static final Set<String> MODULES = Set.of(
        "economysim-item-rater-1_0_0.jar", "economysim-loan-manager-1.0.1.jar",
        "economysim-predicted-prices-1.3.5.jar", "CLAUDE_ADVISOR_ollama_all_market_items.jar");
    private static final Map<String, Integer> EXPECTED_CALLS = Map.of(
        "com/booke/itemrater/OllamaRater", 2,
        "com/booke/loans/LoanAdvisor", 1,
        "com/booke/predictedprices/PricePredictor", 1,
        "com/example/economyadvisor/AdvisorClient", 1);
    private static final Map<String, Integer> calls = new TreeMap<>();
    private static int disabledLoaders;
    private static int removedLifecycle;
    private static int replacedConfig;
    private static Path classes;

    public static void main(String[] args) throws Exception {
        classes = Path.of(args[2]);
        byte[] result = rewrite(Files.readAllBytes(Path.of(args[0])), true, false);
        if (!calls.equals(EXPECTED_CALLS) || disabledLoaders != 2
                || removedLifecycle != 1 || replacedConfig != 1)
            throw new IllegalStateException("Unexpected release layout: calls=" + calls
                + ", loaders=" + disabledLoaders + ", lifecycle=" + removedLifecycle
                + ", config=" + replacedConfig);
        Files.write(Path.of(args[1]), result);
        System.out.println("Patched all five HTTP calls across four AI modules; removed Ollama lifecycle.");
    }

    private static byte[] rewrite(byte[] source, boolean root, boolean aiModule) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream output = new ZipOutputStream(bytes)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] data = input.readAllBytes();
                if (name.equals("com/example/economyadvisor/LocalModelLifecycle.class")) {
                    removedLifecycle++;
                    continue;
                }
                if (root && MODULES.contains(name.substring(name.lastIndexOf('/') + 1)))
                    data = rewrite(data, false, true);
                else if (aiModule && name.equals("com/example/economyadvisor/AdvisorConfig.class")) {
                    data = Files.readAllBytes(classes.resolve(name));
                    replacedConfig++;
                } else if (aiModule && name.endsWith(".class")) data = patchClass(data);
                else if (name.equals("fabric.mod.json")) data = metadata(data, root);
                put(output, name, data);
            }
            if (root) {
                try (var files = Files.walk(classes.resolve("com/booke/nim"))) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                        put(output, classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream output, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0); // Stable archives for a pinned release and toolchain.
        output.putNextEntry(entry);
        output.write(data);
        output.closeEntry();
    }

    private static byte[] metadata(byte[] bytes, boolean root) {
        var json = com.google.gson.JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        // Build metadata keeps >=1.0.0 dependencies valid; a -nim prerelease would not.
        json.addProperty("version", json.get("version").getAsString() + "+nim.1");
        json.addProperty("name", json.get("name").getAsString() + " (NVIDIA NIM)");
        json.addProperty("description", root ? "EconomySim bundle with NVIDIA-hosted AI inference."
                : "NVIDIA NIM edition. Shared key/model: /advisor key paste and /advisor model <model-id>.");
        if (json.has("entrypoints")) {
            var client = json.getAsJsonObject("entrypoints").getAsJsonArray("client");
            if (client != null) {
                for (int i = client.size() - 1; i >= 0; i--)
                    if (client.get(i).getAsString().equals("com.example.economyadvisor.LocalModelLifecycle")) client.remove(i);
            }
        }
        return json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] patchClass(byte[] bytes) throws AnalyzerException {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (FieldNode field : node.fields)
            if (field.value instanceof String text) field.value = text(text);
        for (MethodNode method : node.methods) {
            if ((node.name.equals("com/booke/itemrater/ItemRaterMod") && method.name.equals("loadConfig"))
                    || (node.name.equals("com/booke/loans/AdvisorSettings") && method.name.equals("load"))) {
                clear(method);
                method.instructions.add(new InsnNode(RETURN));
                disabledLoaders++;
                continue;
            }
            if (node.name.equals("com/booke/predictedprices/PricePredictor")
                    && (method.name.equals("endpoint") || method.name.equals("model"))) {
                clear(method);
                method.instructions.add(new MethodInsnNode(INVOKESTATIC, NIM,
                        method.name.equals("endpoint") ? "baseUri" : "model", "()Ljava/lang/String;", false));
                method.instructions.add(new InsnNode(ARETURN));
                method.maxStack = 1;
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("java/net/http/HttpClient")
                        && (call.name.equals("send") || call.name.equals("sendAsync"))) {
                    if (!EXPECTED_CALLS.containsKey(node.name))
                        throw new IllegalStateException("Unexpected network caller " + node.name);
                    call.setOpcode(INVOKESTATIC);
                    call.owner = NIM;
                    call.desc = "(Ljava/net/http/HttpClient;" + call.desc.substring(1);
                    call.itf = false;
                    calls.merge(node.name, 1, Integer::sum);
                } else if (instruction instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC
                        && Set.of("com/booke/itemrater/ItemRaterMod", "com/booke/loans/AdvisorSettings").contains(field.owner)
                        && (field.name.equals("model") || field.name.equals("endpoint"))) {
                    method.instructions.set(field, new MethodInsnNode(INVOKESTATIC, NIM,
                            field.name.equals("endpoint") ? "baseUri" : "model", "()Ljava/lang/String;", false));
                } else if (instruction instanceof LdcInsnNode constant && constant.cst instanceof String string) {
                    constant.cst = text(string);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (int i = 0; i < dynamic.bsmArgs.length; i++)
                        if (dynamic.bsmArgs[i] instanceof String string) dynamic.bsmArgs[i] = text(string);
                }
            }
        }
        for (MethodNode method : node.methods)
            if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0)
                new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void clear(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = 0;
    }

    private static String text(String value) {
        if (value.equals("Cannot reach Ollama. Start Ollama and check endpoint/model in the loan-advisor config."))
            return "Cannot reach NVIDIA NIM. Check your internet connection, /advisor key and /advisor model.";
        return value.replace("http://localhost:11434", "https://integrate.api.nvidia.com")
                .replace("gemma4:12b", "")
                .replace("console.anthropic.com", "build.nvidia.com")
                .replace("ANTHROPIC_API_KEY", "NVIDIA_API_KEY")
                .replace("Anthropic", "NVIDIA")
                .replace("sk-ant-", "nvapi-")
                .replace("Ollama", "NVIDIA NIM")
                .replace("Gemma", "NVIDIA model");
    }
}
