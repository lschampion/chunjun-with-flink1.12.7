import React from "react"
import { graphql } from "gatsby"
import { JSONTree } from "react-json-tree"
import { useSpring, animated } from "react-spring"
import { useContext } from "react"
import { ModeContext } from "../../../components/Context"
const BlogPost = props => {
  let json = {}
  try {
    json = JSON.parse(props.data.jsonContent.content)
  } catch { }
  const theme = {
    scheme: "monokai",
    author: "wimer hazenberg (http://www.monokai.nl)",
    base00: "#272822",
    base01: "#383830",
    base02: "#49483e",
    base03: "#75715e",
    base04: "#a59f85",
    base05: "#f8f8f2",
    base06: "#f5f4f1",
    base07: "#f9f8f5",
    base08: "#f92672",
    base09: "#fd971f",
    base0A: "#f4bf75",
    base0B: "#a6e22e",
    base0C: "#a1efe4",
    base0D: "#66d9ef",
    base0E: "#ae81ff",
    base0F: "#cc6633",
  }
  const aprops = useSpring({
    to: { opacity: 1, left: 0 },
    from: { opacity: 0, left: 100 },
  })
  const { mode } = useContext(ModeContext)
  return (
    <animated.section
      style={aprops}
      className="w-full px-6 py-4 relative 2xl:flex 2xl:justify-center 2xl:items-center 2xl:text-2xl text-base"
    >
      <JSONTree
        valueRenderer={raw => <em className="italic">{raw}</em>}
        labelRenderer={([key]) => <strong className="font-mono">{key}</strong>}
        shouldExpandNode={() => true}
        hideRoot={() => true}
        theme={theme}
        data={json}
        invertTheme={mode !== "dark"}
      />
    </animated.section>
  )
}

export const query = graphql`
  query ($id: String) {
    jsonContent(id: { eq: $id }) {
      id
      content
    }
  }
`

export default BlogPost
