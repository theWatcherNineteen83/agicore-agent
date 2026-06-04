#!/usr/bin/env python3
"""
Metis Codegen Benchmark — 10 Java Tasks
Misst pass@1 und pass@3 fuer verschiedene Ollama-Modelle.
"""
import json, sys, subprocess, tempfile, os, time, re, urllib.request

OLLAMA_URL = "http://localhost:11434/api/generate"
BENCHMARK_MODELS = ["gemma4:31b", "qwen3.6:27b-q4_K_M"]
JAVA_HOME = "/opt/zing25-jdk" if os.path.isdir("/opt/zing25-jdk") else os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-25-zulu")
JAVAC = f"{JAVA_HOME}/bin/javac" if os.path.isdir(f"{JAVA_HOME}/bin") else "javac"
JAVA = f"{JAVA_HOME}/bin/java" if os.path.isdir(f"{JAVA_HOME}/bin") else "java"

TASKS = [
    {
        "name": "string_reverse",
        "task": "Write a class StringUtils with a static method reverse(String s) that reverses a string.",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        boolean ok = StringUtils.reverse("hello").equals("olleh")
                  && StringUtils.reverse("").equals("")
                  && StringUtils.reverse("a").equals("a");
        System.out.println(ok ? "PASS" : "FAIL: reverse tests");
    }
}""",
        "className": "StringUtils"
    },
    {
        "name": "fibonacci",
        "task": "Write a class Fibonacci with a static method fib(int n) returning the nth Fibonacci number (0-indexed).",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        boolean ok = Fibonacci.fib(0) == 0 && Fibonacci.fib(1) == 1 && Fibonacci.fib(10) == 55;
        System.out.println(ok ? "PASS" : "FAIL: fib");
    }
}""",
        "className": "Fibonacci"
    },
    {
        "name": "is_palindrome",
        "task": "Write a class Palindrome with a static method isPalindrome(String s) returning boolean.",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        boolean ok = Palindrome.isPalindrome("racecar") && !Palindrome.isPalindrome("hello") && Palindrome.isPalindrome("");
        System.out.println(ok ? "PASS" : "FAIL: palindrome");
    }
}""",
        "className": "Palindrome"
    },
    {
        "name": "array_sum",
        "task": "Write a class ArrayUtils with a static method sum(int[] arr) returning sum of elements.",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        boolean ok = ArrayUtils.sum(new int[]{1,2,3}) == 6 && ArrayUtils.sum(new int[]{}) == 0;
        System.out.println(ok ? "PASS" : "FAIL: sum");
    }
}""",
        "className": "ArrayUtils"
    },
    {
        "name": "word_count",
        "task": "Write a class WordCounter with a static method countWords(String text) returning the number of words (split by spaces).",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        boolean ok = WordCounter.countWords("hello world") == 2 && WordCounter.countWords("") == 0;
        System.out.println(ok ? "PASS" : "FAIL: wordCount");
    }
}""",
        "className": "WordCounter"
    },
    {
        "name": "list_filter",
        "task": "Write a class ListFilter with a static method filterEven(int[] numbers) returning int[] with only even numbers.",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        int[] r = ListFilter.filterEven(new int[]{1,2,3,4,5,6});
        boolean ok = r.length == 3 && r[0] == 2 && r[1] == 4 && r[2] == 6;
        System.out.println(ok ? "PASS" : "FAIL: filterEven");
    }
}""",
        "className": "ListFilter"
    },
    {
        "name": "string_to_int",
        "task": "Write a class StringConverter with a static method toInt(String s) converting string to int, returning 0 on invalid input.",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        boolean ok = StringConverter.toInt("123") == 123 && StringConverter.toInt("abc") == 0 && StringConverter.toInt("") == 0;
        System.out.println(ok ? "PASS" : "FAIL: toInt");
    }
}""",
        "className": "StringConverter"
    },
    {
        "name": "max_element",
        "task": "Write a class ArrayMax with a static method max(int[] arr) returning max, returns Integer.MIN_VALUE for empty.",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        boolean ok = ArrayMax.max(new int[]{3,7,2,9,1}) == 9;
        System.out.println(ok ? "PASS" : "FAIL: max");
    }
}""",
        "className": "ArrayMax"
    },
    {
        "name": "char_frequency",
        "task": "Write a class CharFrequency with a static method mostFrequent(String s) returning most frequent char, returns '\\0' for empty.",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        boolean ok = CharFrequency.mostFrequent("aabbbcc") == 'b';
        System.out.println(ok ? "PASS" : "FAIL: mostFrequent");
    }
}""",
        "className": "CharFrequency"
    },
    {
        "name": "matrix_transpose",
        "task": "Write a class MatrixOps with a static method transpose(int[][] m) returning int[][] transposed matrix.",
        "test_code": """
public class TestRunner {
    public static void main(String[] a) {
        int[][] r = MatrixOps.transpose(new int[][]{{1,2},{3,4}});
        boolean ok = r.length == 2 && r[0][0] == 1 && r[1][0] == 2;
        System.out.println(ok ? "PASS" : "FAIL: transpose");
    }
}""",
        "className": "MatrixOps"
    },
]

def strip_code(text):
    m = re.search(r'```(?:java)?\n(.*?)```', text, re.DOTALL)
    return m.group(1).strip() if m else text.strip()

def compile_and_test(code, class_name, test_code, out_dir):
    """Compile and run test. Returns (compiled, test_passed, errors)."""
    src = os.path.join(out_dir, f"{class_name}.java")
    with open(src, "w") as f:
        f.write(code)
    result = subprocess.run(
        [JAVAC, "-d", out_dir, "-source", "25", "-target", "25", src],
        capture_output=True, text=True, timeout=15
    )
    if result.returncode != 0:
        return False, False, result.stderr.strip()

    # Write and compile test runner
    test_src = os.path.join(out_dir, "TestRunner.java")
    with open(test_src, "w") as f:
        f.write(test_code)
    test_result = subprocess.run(
        [JAVAC, "-d", out_dir, "-cp", out_dir, "-source", "25", "-target", "25", test_src],
        capture_output=True, text=True, timeout=15
    )
    if test_result.returncode != 0:
        return True, False, test_result.stderr.strip()

    # Run test
    run_result = subprocess.run(
        [JAVA, "-cp", out_dir, "TestRunner"],
        capture_output=True, text=True, timeout=10
    )
    passed = "PASS" in run_result.stdout
    return True, passed, run_result.stdout.strip()

def run_task(task, model):
    print(f"  [{model}] {task['name']}...", end=" ", flush=True)

    prompt = (
        f"Write a Java class {task['className']} that compiles on Java 25.\n\n"
        f"TASK: {task['task']}\n\n"
        f"Return ONLY the code in ```java ... ```. "
        f"No explanation, no extra classes. Keep exact class name: {task['className']}."
    )

    with tempfile.TemporaryDirectory() as tmpdir:
        # Pass 1
        code = generate(prompt, model)
        if not code:
            print("GEN_FAIL")
            return ("FAIL", "FAIL", "FAIL")

        results = []
        for attempt in range(3):
            code = strip_code(code)
            compiled, passed, errors = compile_and_test(code, task['className'], task['test_code'], tmpdir)
            result = "PASS" if compiled and passed else ("COMPILE_OK" if compiled else "COMPILE_FAIL")
            results.append(result)
            print(f"{attempt+1}:{result}", end=" ", flush=True)
            if compiled and passed:
                break
            if attempt < 2:
                # Repair prompt with error feedback
                error_detail = errors if not compiled else "Test failed"
                prompt = (
                    f"Fix the Java class {task['className']}.\n\n"
                    f"TASK: {task['task']}\n\n"
                    f"PREVIOUS CODE:\n```java\n{code}\n```\n\n"
                    f"ERRORS:\n{error_detail}\n\n"
                    f"Return ONLY the corrected code. Keep class name: {task['className']}."
                )
                code = generate(prompt, model)
                if not code:
                    print("GEN_FAIL", end=" ")
                    break

        print()
        p1 = results[0] if len(results) > 0 else "FAIL"
        p3 = results[-1] if len(results) > 0 else "FAIL"
        return (p1, p3, results)

def generate(prompt, model):
    try:
        payload = json.dumps({
            "model": model,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": 0.15, "num_predict": 2048}
        }).encode()
        req = urllib.request.Request(OLLAMA_URL, data=payload,
            headers={"Content-Type": "application/json"})
        resp = urllib.request.urlopen(req, timeout=120)
        data = json.loads(resp.read())
        text = data.get("response", "").strip()
        m = re.search(r'```(?:java)?\n(.*?)```', text, re.DOTALL)
        return m.group(1).strip() if m else text
    except Exception as e:
        print(f"  API_ERROR:{e}")
        return None

def main():
    models = sys.argv[1:] if len(sys.argv) > 1 else BENCHMARK_MODELS
    print(f"Metis Codegen Benchmark — {len(TASKS)} Tasks, {len(models)} Model(s)")
    print("=" * 60)

    for model in models:
        print(f"\n{'='*60}\nModel: {model}\n{'='*60}")
        results = []
        for task in TASKS:
            p1, p3, all_results = run_task(task, model)
            results.append((task['name'], p1, all_results))

        pass1 = sum(1 for _, r, _ in results if r in ("PASS", "COMPILE_OK"))
        pass3 = sum(1 for _, _, ar in results if ar and ar[-1] in ("PASS", "COMPILE_OK"))
        n = len(results)
        print(f"\n{'='*60}")
        print(f"{model} Results:")
        print(f"  pass@1: {pass1}/{n} = {pass1/n*100:.0f}%")
        print(f"  pass@3: {pass3}/{n} = {pass3/n*100:.0f}%")
        for name, r, ar in results:
            status = "PASS" if r in ("PASS", "COMPILE_OK") else "FAIL"
            seq = "/".join(ar) if ar else "FAIL"
            print(f"  {name:20s}: {status} ({seq})")

    print("\nDone.")

if __name__ == "__main__":
    main()
