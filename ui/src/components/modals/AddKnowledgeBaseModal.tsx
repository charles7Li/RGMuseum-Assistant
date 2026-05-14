import React, { useState } from "react";
import { Button, Input, Modal, Select } from "antd";
import TextArea from "antd/es/input/TextArea";
import { SaveOutlined } from "@ant-design/icons";
import {
  type CreateKnowledgeBaseRequest,
  type EmbeddingRule,
} from "../../api/api.ts";

interface AddKnowledgeBaseModalProps {
  open: boolean;
  onClose: () => void;
  createKnowledgeBaseHandle: (
    request: CreateKnowledgeBaseRequest,
  ) => Promise<void>;
}

const AddKnowledgeBaseModal: React.FC<AddKnowledgeBaseModalProps> = ({
  open,
  onClose,
  createKnowledgeBaseHandle,
}) => {
  const [formData, setFormData] = useState<CreateKnowledgeBaseRequest>({
    name: "",
    description: "",
    embeddingRule: "title+content(500)",
  });
  
  const [createLoading, setCreateLoading] = useState(false);
  
  const handleSubmit = async () => {
    if (!formData.name.trim()) {
      return;
    }
    setCreateLoading(true);
    
    try {
      await createKnowledgeBaseHandle(formData);
      // 重置表单
      setFormData({
        name: "",
        description: "",
        embeddingRule: "title+content(500)",
      });
      onClose();
    } finally {
      setCreateLoading(false);
    }
  };

  const handleCancel = () => {
    // 重置表单
    setFormData({
      name: "",
      description: "",
      embeddingRule: "title+content(500)",
    });
    onClose();
  };

  const embeddingOptions: { value: EmbeddingRule; label: string }[] = [
    { value: "title-only", label: "title-only（仅标题）" },
    { value: "title+content(500)", label: "title+content(500)（推荐）" },
    { value: "content-only(500)", label: "content-only(500)（仅正文）" },
  ];

  return (
    <Modal
      open={open}
      onCancel={handleCancel}
      title="新建知识库"
      footer={null}
      width={600}
      centered
    >
      <div className="py-4">
        <div className="mb-4">
          <label className="block text-gray-700 font-medium mb-2">
            名称 <span className="text-red-500">*</span>
          </label>
          <Input
            placeholder="请输入知识库名称"
            value={formData.name}
            onChange={(e) =>
              setFormData({ ...formData, name: e.target.value })
            }
            onPressEnter={handleSubmit}
          />
        </div>
        <div className="mb-6">
          <label className="block text-gray-700 font-medium mb-2">
            描述
          </label>
          <TextArea
            placeholder="请输入知识库描述（可选）"
            rows={4}
            value={formData.description}
            onChange={(e) =>
              setFormData({ ...formData, description: e.target.value })
            }
          />
        </div>
        <div className="mb-6">
          <label className="block text-gray-700 font-medium mb-2">
            Embedding 规则
          </label>
          <Select
            className="w-full"
            value={formData.embeddingRule}
            options={embeddingOptions}
            onChange={(value) =>
              setFormData({
                ...formData,
                embeddingRule: value as EmbeddingRule,
              })
            }
          />
        </div>
        <div className="flex justify-end gap-2">
          <Button onClick={handleCancel}>取消</Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={createLoading}
            onClick={handleSubmit}
            disabled={!formData.name.trim()}
          >
            创建
          </Button>
        </div>
      </div>
    </Modal>
  );
};

export default AddKnowledgeBaseModal;
