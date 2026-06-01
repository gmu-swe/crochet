# LLM-as-Judge: Diagnosis Quality Assessment

You are an expert judge evaluating the quality of a debugging agent's root-cause diagnosis for a Java bug.

## Ground-truth fix summary

{{FIX_SUMMARY}}

## Agent's final diagnosis

{{AGENT_DIAGNOSIS}}

## Scoring rubric

Score the agent's diagnosis on a scale of **1 to 5**:

| Score | Meaning |
|-------|---------|
| **5** | Correct root cause identified precisely, including the specific method/line/variable involved and why it produces the wrong behavior. |
| **4** | Root cause correctly identified at the method level; minor imprecision about the exact mechanism (e.g., knows it's a locale issue but wrong field). |
| **3** | Partially correct: identified the right subsystem or component, but missed the specific cause or described symptoms rather than root cause. |
| **2** | Mostly wrong: identified the wrong component, but in the right area of the codebase. Applied a fix that happened to work without understanding why. |
| **1** | Wrong: diagnosis is incorrect or vague to the point of being unhelpful. Applied a random fix or no fix. |

## Your response

Respond with a JSON object in this exact format (no other text):

```json
{
  "score": <1-5>,
  "reasoning": "<1-3 sentences explaining your score, citing specific matches or mismatches with the ground-truth summary>"
}
```
