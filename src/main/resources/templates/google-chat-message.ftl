<#if trigger == "start">
<#assign message="<font color=#ffa500><b><a href=${executionData.job.href}>${executionData.job.name}</a> has started</b></font>">
<#elseif trigger == "failure">
<#assign message="<font color=#ff0000><b><a href=${executionData.job.href}>${executionData.job.name}</a> has failed</b></font>">
<#else>
<#assign message="<font color=#008000><b><a href=${executionData.job.href}>${executionData.job.name}</a> has succeeded</b></font>">
</#if>
{

  "previewText": "",
  "fallbackText": "${executionData.job.description}",
  "cards": [
  {

   "header": {
        "title": "Rundeck job",
        "subtitle": "${executionData.job.name}",
        "imageUrl": "https://camo.githubusercontent.com/c238160fa93fa0668da72983a3276967b71006f7/68747470733a2f2f63646e2e7261776769742e636f6d2f6d616a6b696e65746f722f63686f636f6c617465792f6d61737465722f72756e6465636b2f69636f6e2e706e67",
        "imageStyle": "IMAGE"
      },

      "sections": [
        {

  "widgets": [
            {
"textParagraph": {
"text": "<#if (executionData.job.description)?has_content>User: <b>${executionData.user}</b>
<i>${executionData.job.description}</i>
</#if>
<#if (executionData.job.group)?has_content>
in <b>${executionData.job.group}</b>
</#if>
${message}
<#if trigger == "start">
<b><font color=#ffa500>Job started!</font></b>
</#if>
<#if (executionData.failedNodeListString)?has_content>
<b><font color=#ff0000>Failed on node ${executionData.failedNodeListString}</font></b>
</#if>
<#if (executionData.succeededNodeListString)?has_content>
<b><font color=#008000>Succeded on node ${executionData.succeededNodeListString}</font></b>
</#if>
<#if (executionData.context.option.target)?has_content>
Against host ${executionData.context.option.target}
</#if>
<a href=${executionData.href}>view output</a>
"
              }
            }
          ]
        }
      ]
    }
  ]

}
